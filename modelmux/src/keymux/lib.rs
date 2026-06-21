//! keymux library - Plugin system for keyboard-driven applications.

pub mod cli;
pub mod event_bus;
pub mod keymap;
pub mod plugin;

pub use event_bus::{EventBus, KeyEvent, KeyChord, Modifier};
pub use keymap::{KeyMap, KeyBinding, Action};
pub use plugin::{Plugin, PluginContext, PluginConfig, PluginMetadata, PluginRegistry, PluginManager, ActionResult, TrikeShedKanbanPlugin};