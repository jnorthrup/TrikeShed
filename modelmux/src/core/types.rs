//! Core types shared across keymux, modelmux, and reactor

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::Semaphore;
use chrono::{DateTime, Utc, Local};

/// Credential pool structure from auth.json
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CredentialPool {
    #[serde(default)]
    pub credential_pool: HashMap<String, Vec<CredentialEntry>>,
}

/// Individual credential entry in the pool
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CredentialEntry {
    pub id: String,
    pub label: String,
    #[serde(rename = "auth_type")]
    pub auth_type: String,
    pub priority: i32,
    pub source: String,
    #[serde(rename = "last_status")]
    pub last_status: Option<String>,
    #[serde(rename = "last_status_at")]
    pub last_status_at: Option<f64>,
    #[serde(rename = "last_error_code")]
    pub last_error_code: Option<i32>,
    #[serde(rename = "last_error_reason")]
    pub last_error_reason: Option<String>,
    #[serde(rename = "last_error_message")]
    pub last_error_message: Option<String>,
    #[serde(rename = "last_error_reset_at")]
    pub last_error_reset_at: Option<f64>,
    #[serde(rename = "base_url")]
    pub base_url: String,
    #[serde(rename = "request_count")]
    pub request_count: u64,
    #[serde(rename = "secret_fingerprint")]
    pub secret_fingerprint: Option<String>,
    // OAuth fields
    #[serde(rename = "access_token")]
    pub access_token: Option<String>,
    #[serde(rename = "refresh_token")]
    pub refresh_token: Option<String>,
    #[serde(rename = "expires_at")]
    pub expires_at: Option<String>,
    // Chimera tracking fields
    #[serde(rename = "last_model")]
    pub last_model: Option<String>,
    #[serde(rename = "last_used_at")]
    pub last_used_at: Option<u64>,
}

/// Key status enum
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum KeyStatus {
    Draft,
    Exhausted,
    Dead,
}

/// Key state with chimera tracking (semaphore for single-agent-per-key)
#[derive(Debug, Clone)]
pub struct KeyState {
    pub id: String,
    pub provider: String,
    pub label: String,
    pub base_url: String,
    pub model: String,
    pub last_used: u64,
    pub status: KeyStatus,
    pub context_limit: usize,
    // Chimera: per-key semaphore for single-agent-per-key exclusivity
    pub semaphore: Arc<Semaphore>,
    // Chimera: lease tracking
    pub leased_to: Option<String>,
    pub lease_expires_at: Option<u64>,
}

/// Quota tracking for a provider
#[derive(Debug, Clone, Default)]
pub struct QuotaTracking {
    pub provider: String,
    pub tokens_used_today: u64,
    pub tokens_used_this_hour: u64,
    pub tokens_used_this_month: u64,
    pub daily_limit: Option<u64>,
    pub hourly_limit: Option<u64>,
    pub monthly_limit: Option<u64>,
    pub last_reset_day: u32,
    pub last_reset_hour: u32,
    pub last_reset_month: u32,
}

impl QuotaTracking {
    pub fn new(
        provider: String,
        daily: Option<u64>,
        hourly: Option<u64>,
        monthly: Option<u64>,
    ) -> Self {
        let now = Local::now();
        Self {
            provider,
            daily_limit: daily,
            hourly_limit: hourly,
            monthly_limit: monthly,
            last_reset_day: now.day(),
            last_reset_hour: now.hour(),
            last_reset_month: now.month(),
            ..Default::default()
        }
    }

    pub fn check_and_reset(&mut self) {
        let now = Local::now();
        let current_day = now.day();
        let current_hour = now.hour();
        let current_month = now.month();

        if current_day != self.last_reset_day {
            self.tokens_used_today = 0;
            self.last_reset_day = current_day;
        }
        if current_hour != self.last_reset_hour {
            self.tokens_used_this_hour = 0;
            self.last_reset_hour = current_hour;
        }
        if current_month != self.last_reset_month {
            self.tokens_used_this_month = 0;
            self.last_reset_month = current_month;
        }
    }

    pub fn has_quota(&mut self, tokens: u64) -> bool {
        self.check_and_reset();
        (self.daily_limit.is_none() || self.tokens_used_today + tokens <= self.daily_limit.unwrap())
            && (self.hourly_limit.is_none()
                || self.tokens_used_this_hour + tokens <= self.hourly_limit.unwrap())
            && (self.monthly_limit.is_none()
                || self.tokens_used_this_month + tokens <= self.monthly_limit.unwrap())
    }

    pub fn record_usage(&mut self, tokens: u64) {
        self.check_and_reset();
        self.tokens_used_today += tokens;
        self.tokens_used_this_hour += tokens;
        self.tokens_used_this_month += tokens;
    }

    pub fn estimated_remaining_today(&self) -> u64 {
        self.daily_limit
            .map(|lim| lim.saturating_sub(self.tokens_used_today))
            .unwrap_or(u64::MAX)
    }
}

/// Provider status for monitoring
#[derive(Debug, Clone, Serialize)]
pub struct ProviderStatus {
    pub name: String,
    pub base_url: String,
    pub has_key: bool,
    pub priority: i32,
    pub is_free: bool,
    pub quota_used_today: u64,
    pub quota_remaining_today: u64,
}

/// Model info for /v1/models endpoint
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModelInfo {
    pub id: String,
    pub owned_by: String,
    #[serde(default = "default_object")]
    pub object: String,
    #[serde(default = "default_created")]
    pub created: u64,
}

fn default_object() -> String {
    "model".to_string()
}
fn default_created() -> u64 {
    Utc::now().timestamp() as u64
}

/// Models response
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModelsResponse {
    #[serde(default = "default_object")]
    pub object: String,
    pub data: Vec<ModelInfo>,
}

/// Chat completion request
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChatCompletionRequest {
    pub model: String,
    pub messages: Vec<ChatMessage>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub temperature: Option<f32>,
    #[serde(skip_serializing_if = "Option::is_none", rename = "max_tokens")]
    pub max_tokens: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub stream: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none", rename = "tool_choice")]
    pub tool_choice: Option<serde_json::Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tools: Option<Vec<serde_json::Value>>,
}

/// Chat message
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChatMessage {
    pub role: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub content: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none", rename = "tool_calls")]
    pub tool_calls: Option<Vec<serde_json::Value>>,
    #[serde(skip_serializing_if = "Option::is_none", rename = "tool_call_id")]
    pub tool_call_id: Option<String>,
}

/// Choice in completion response
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Choice {
    pub index: i32,
    pub message: ChatMessage,
    #[serde(skip_serializing_if = "Option::is_none", rename = "finish_reason")]
    pub finish_reason: Option<String>,
}

/// Token usage
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Usage {
    #[serde(rename = "prompt_tokens")]
    pub prompt_tokens: i32,
    #[serde(rename = "completion_tokens")]
    pub completion_tokens: i32,
    #[serde(rename = "total_tokens")]
    pub total_tokens: i32,
}

/// Chat completion response
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChatCompletionResponse {
    pub id: String,
    pub object: String,
    pub created: u64,
    pub model: String,
    pub choices: Vec<Choice>,
    pub usage: Option<Usage>,
}

/// Route result for a model request
#[derive(Debug, Clone)]
pub struct RouteResult {
    pub provider: String,
    pub base_url: String,
    pub api_key: String,
}

/// Context limits per provider/model
pub fn get_context_limit(provider: &str, model: &str) -> usize {
    let limits = match provider {
        "zai" => vec![("glm-5", 128_000), ("glm-4", 128_000), ("glm-4.5", 128_000)],
        "copilot" => vec![("gpt-4o", 128_000), ("gpt-4o-mini", 128_000), ("gpt-4", 128_000)],
        "minimax" => vec![("minimax-01", 256_000), ("abab-6.5s", 256_000)],
        "openai-codex" => vec![("gpt-5", 272_000), ("gpt-4o", 128_000)],
        "nous" => vec![("hermes-3-70b", 256_000), ("hermes-3-8b", 256_000)],
        "nvidia" => vec![("nemotron-3-ultra", 128_000)],
        _ => vec![],
    };

    limits
        .iter()
        .find(|(m, _)| model.contains(*m))
        .map(|(_, lim)| *lim)
        .unwrap_or_else(|| match provider {
            "zai" => 128_000,
            "copilot" => 128_000,
            "minimax" => 256_000,
            "openai-codex" => 272_000,
            "nous" => 256_000,
            "nvidia" => 128_000,
            _ => 128_000,
        })
}

/// Chimera global draft semaphore (protects bijective key_id -> model mutations)
static CHIMERA_DRAFT_SEMAPHORE: std::sync::OnceLock<Arc<Semaphore>> = std::sync::OnceLock::new();

pub fn get_draft_semaphore() -> Arc<Semaphore> {
    CHIMERA_DRAFT_SEMAPHORE
        .get_or_init(|| Arc::new(Semaphore::new(1)))
        .clone()
}