//! Configuration module - loads .env files and environment variables
//! Equivalent to Kotlin's ModelMuxConfig

use crate::core::CredentialPool;
use std::collections::HashMap;
use std::env;
use std::path::{Path, PathBuf};
use anyhow::Result;
use dotenvy::from_path_iter;
use tracing::{info, warn};

/// Configuration precedence: env var > .env.local > .env > .env.default > .env.example
const CONFIG_FILES: &[&str] = &[
    ".env.example",
    ".env.default",
    ".env",
    ".env.local",
];

/// ModelMux configuration
#[derive(Debug, Clone, Default)]
pub struct ModelMuxConfig {
    props: HashMap<String, String>,
    loaded: bool,
    config_dir: PathBuf,
}

impl ModelMuxConfig {
    /// Create new config with default directory (current dir)
    pub fn new() -> Self {
        Self {
            config_dir: PathBuf::from("."),
            ..Default::default()
        }
    }

    /// Create new config with specific directory
    pub fn with_dir(config_dir: PathBuf) -> Self {
        Self {
            config_dir,
            ..Default::default()
        }
    }

    /// Load configuration from all sources
    pub async fn load(&mut self) -> Result<&mut Self> {
        if self.loaded {
            return Ok(self);
        }

        info!("Loading configuration from {:?}", self.config_dir);

        // Load files in precedence order (later files override earlier)
        for file_name in CONFIG_FILES {
            let file_path = self.config_dir.join(file_name);
            if file_path.exists() {
                self.load_properties_file(&file_path)?;
            }
        }

        // Environment variables always win - load them last
        self.load_environment_variables();

        self.loaded = true;
        info!("Configuration loaded successfully");
        Ok(self)
    }

    fn load_properties_file(&mut self, file_path: &Path) -> Result<()> {
        let iter = from_path_iter(file_path)?;
        for item in iter {
            match item {
                Ok((key, value)) => {
                    self.props.insert(key, value);
                }
                Err(e) => {
                    warn!("Failed to parse {}: {}", file_path.display(), e);
                }
            }
        }
        Ok(())
    }

    fn load_environment_variables(&mut self) {
        for (key, value) in env::vars() {
            self.props.insert(key, value);
        }
    }

    /// Get string value with optional default
    pub fn get_string(&self, key: &str) -> Option<String> {
        self.props.get(key).cloned()
    }

    /// Get required string value (error if missing)
    pub fn get_required_string(&self, key: &str) -> Result<String> {
        self.props.get(key)
            .cloned()
            .ok_or_else(|| anyhow::anyhow!("Required config '{}' not found. Check .env files and environment variables.", key))
    }

    /// Get int value with optional default
    pub fn get_int(&self, key: &str, default: i32) -> i32 {
        self.props.get(key).and_then(|v| v.parse().ok()).unwrap_or(default)
    }

    /// Get long value with optional default
    pub fn get_long(&self, key: &str, default: i64) -> i64 {
        self.props.get(key).and_then(|v| v.parse().ok()).unwrap_or(default)
    }

    /// Get bool value with optional default
    pub fn get_bool(&self, key: &str, default: bool) -> bool {
        self.props.get(key).and_then(|v| v.parse().ok()).unwrap_or(default)
    }

    /// Get list of strings (comma-separated)
    pub fn get_string_list(&self, key: &str) -> Vec<String> {
        self.props.get(key)
            .map(|v| v.split(',').map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect())
            .unwrap_or_default()
    }

    /// Check if a key exists and has a non-placeholder value
    pub fn has_real_value(&self, key: &str) -> bool {
        self.props.get(key).map(|v| Self::is_real_value(v)).unwrap_or(false)
    }

    /// Check if a string value is a real value (not placeholder)
    fn is_real_value(value: &str) -> bool {
        let v = value.trim();
        if v.len() < 10 { return false; }
        if v.starts_with("your_") || v.starts_with("***") { return false; }
        if v.to_lowercase().contains("xxx") || v.to_lowercase().contains("here") || v.to_uppercase().contains("TODO") { return false; }
        true
    }

    /// Get all keys with a prefix
    pub fn get_keys_with_prefix(&self, prefix: &str) -> HashMap<String, String> {
        self.props.iter()
            .filter(|(k, _)| k.starts_with(prefix))
            .map(|(k, v)| (k.clone(), v.clone()))
            .collect()
    }

    /// Print all loaded config (sanitized - hides secrets)
    pub fn print_config(&self) {
        println!("=== ModelMux Configuration ===");
        let mut keys: Vec<_> = self.props.keys().collect();
        keys.sort();
        for key in keys {
            let value = self.props.get(key).unwrap();
            let display_value = if key.to_uppercase().contains("KEY") 
                || key.to_uppercase().contains("SECRET") 
                || key.to_uppercase().contains("TOKEN") {
                if value.len() > 8 {
                    format!("{}...{}", &value[..4], &value[value.len()-4..])
                } else {
                    "****".to_string()
                }
            } else {
                value.clone()
            };
            println!("  {}={}", key, display_value);
        }
        println!("==============================");
    }

    /// Load credential pool from auth.json
    pub async fn load_credential_pool(&self) -> Result<CredentialPool> {
        let auth_path = dirs::home_dir()
            .map(|h| h.join(".hermes").join("auth.json"))
            .ok_or_else(|| anyhow::anyhow!("Could not find home directory"))?;

        if !auth_path.exists() {
            return Ok(CredentialPool { credential_pool: HashMap::new() });
        }

        let content = tokio::fs::read_to_string(&auth_path).await?;
        let pool: CredentialPool = serde_json::from_str(&content)?;
        Ok(pool)
    }
}

/// Configuration keys as constants for type-safe access
pub mod keys {
    // Server
    pub const PORT: &str = "MODELMUX_PORT";
    pub const BIND_ADDRESS: &str = "MODELMUX_BIND_ADDRESS";
    pub const REQUEST_TIMEOUT_SECS: &str = "MODELMUX_REQUEST_TIMEOUT_SECS";
    pub const MAX_RETRIES: &str = "MODELMUX_MAX_RETRIES";
    pub const ENABLE_STREAMING: &str = "MODELMUX_ENABLE_STREAMING";
    pub const ENABLE_CACHING: &str = "MODELMUX_ENABLE_CACHING";

    // Models
    pub const DEFAULT_MODEL: &str = "MODELMUX_DEFAULT_MODEL";
    pub const FALLBACK_MODEL: &str = "MODELMUX_FALLBACK_MODEL";
    pub const MAX_CONTEXT_WINDOW: &str = "MODELMUX_MAX_CONTEXT_WINDOW";

    // Provider API Keys
    pub const OPENAI_API_KEY: &str = "OPENAI_API_KEY";
    pub const OPENAI_BASE_URL: &str = "OPENAI_BASE_URL";
    pub const ANTHROPIC_API_KEY: &str = "ANTHROPIC_API_KEY";
    pub const ANTHROPIC_BASE_URL: &str = "ANTHROPIC_BASE_URL";
    pub const KILO_API_KEY: &str = "KILO_API_KEY";
    pub const KILO_BASE_URL: &str = "KILO_BASE_URL";
    pub const KILOCODE_API_KEY: &str = "KILOCODE_API_KEY";
    pub const KILOAI_API_KEY: &str = "KILOAI_API_KEY";
    pub const MOONSHOT_API_KEY: &str = "MOONSHOT_API_KEY";
    pub const MOONSHOT_BASE_URL: &str = "MOONSHOT_BASE_URL";
    pub const DEEPSEEK_API_KEY: &str = "DEEPSEEK_API_KEY";
    pub const DEEPSEEK_BASE_URL: &str = "DEEPSEEK_BASE_URL";
    pub const GROQ_API_KEY: &str = "GROQ_API_KEY";
    pub const GROQ_BASE_URL: &str = "GROQ_BASE_URL";
    pub const NVIDIA_API_KEY: &str = "NVIDIA_API_KEY";
    pub const NVIDIA_BASE_URL: &str = "NVIDIA_BASE_URL";
    pub const OPENROUTER_API_KEY: &str = "OPENROUTER_API_KEY";
    pub const OPENROUTER_BASE_URL: &str = "OPENROUTER_BASE_URL";
    pub const CEREBRAS_API_KEY: &str = "CEREBRAS_API_KEY";
    pub const CEREBRAS_BASE_URL: &str = "CEREBRAS_BASE_URL";
    pub const XAI_API_KEY: &str = "XAI_API_KEY";
    pub const XAI_BASE_URL: &str = "XAI_BASE_URL";
    pub const GEMINI_API_KEY: &str = "GEMINI_API_KEY";
    pub const GEMINI_BASE_URL: &str = "GEMINI_BASE_URL";
    pub const PERPLEXITY_API_KEY: &str = "PERPLEXITY_API_KEY";
    pub const PERPLEXITY_BASE_URL: &str = "PERPLEXITY_BASE_URL";
    pub const ZENMUX_API_KEY: &str = "ZENMUX_API_KEY";
    pub const ZENMUX_BASE_URL: &str = "ZENMUX_BASE_URL";
    pub const OPencode_API_KEY: &str = "OPENCODE_API_KEY";
    pub const OPencode_BASE_URL: &str = "OPENCODE_BASE_URL";

    // Optional features
    pub const ENABLE_OLLAMA_OPENROUTER: &str = "MODELMUX_ENABLE_OLLAMA_OPENROUTER";
    pub const ENABLE_OPENROUTER_FALLBACK: &str = "MODELMUX_ENABLE_OPENROUTER_FALLBACK";
    pub const INCLUDE_OPENROUTER_MODELS: &str = "MODELMUX_INCLUDE_OPENROUTER_MODELS";
    pub const OPENROUTER_FREE_MODEL: &str = "OPENROUTER_FREE_MODEL";
    pub const HUGGINGFACE_API_KEY: &str = "HUGGINGFACE_API_KEY";

    // Search
    pub const BRAVE_SEARCH_API_KEY: &str = "BRAVE_SEARCH_API_KEY";
    pub const TAVILY_SEARCH_API_KEY: &str = "TAVILY_SEARCH_API_KEY";

    // Logging
    pub const RUST_LOG: &str = "RUST_LOG";
}