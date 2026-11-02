// Narrow text/tool transport for the actual pinned Pi Agent; host owns model and session.
import { Agent } from '@earendil-works/pi-agent-core';
import { AssistantMessageEventStream } from '@earendil-works/pi-ai';
import { getCurrentTools, getCurrentSystemPrompt } from '@earendil-works/pi-ai';

function plainClone(value) {
  if (value === null || typeof value === 'string' || typeof value === 'boolean') return value;
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (Array.isArray(value)) return value.map(plainClone);
  if (value && Object.getPrototypeOf(value) === Object.prototype)
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, plainClone(item)]));
  throw new TypeError('Pi transport accepts JSON values only');
}
// Only the upstream tool validator calls this, for bounded JSON tool arguments.
if (typeof structuredClone === 'undefined') globalThis.structuredClone = plainClone;
if (typeof performance === 'undefined') globalThis.performance = Object.freeze({ now: () => host.call('pi_clock') });
if (typeof AbortController === 'undefined') {
  globalThis.AbortController = class {
    constructor() {
      const listeners = new Map();
      this.signal = {
        aborted: false, reason: undefined,
        addEventListener(type, fn, options) { if (type === 'abort') listeners.set(fn, !!options?.once); },
        removeEventListener(type, fn) { if (type === 'abort') listeners.delete(fn); },
        throwIfAborted() { if (this.aborted) throw this.reason; },
      };
      this.abort = reason => {
        if (this.signal.aborted) return;
        this.signal.aborted = true;
        this.signal.reason = reason ?? Object.assign(new Error('Aborted'), { name: 'AbortError' });
        for (const [fn, once] of listeners) {
          if (once) listeners.delete(fn);
          if (typeof fn === 'function') fn.call(this.signal, { type: 'abort', target: this.signal });
          else fn.handleEvent({ type: 'abort', target: this.signal });
        }
      };
    }
  };
}
function crossing(name, ...args) {
  const reply = JSON.parse(host.call(name, ...args));
  if (reply.verdict !== 'ok') throw new Error(reply.verdict + ': ' + (reply.detail || 'host refused'));
  return reply;
}
function text(content) {
  if (!Array.isArray(content) || content.some(p => p.type !== 'text')) throw Error('only text input is supported');
  return content.map(p => p.text).join('');
}
function request(context) {
  const messages = [];
  const system = getCurrentSystemPrompt(context.messages);
  if (system) messages.push({ role: 'system', content: system });
  for (const m of context.messages) {
    if (m.role === 'system') continue;
    if (m.role === 'user') messages.push({ role: 'user', content: text(m.content) });
    else if (m.role === 'toolResult') messages.push({ role: 'tool', tool_call_id: m.toolCallId, content: text(m.content) });
    else if (m.role === 'assistant') {
      const content = m.content.filter(p => p.type === 'text').map(p => p.text).join('');
      const tool_calls = m.content.filter(p => p.type === 'toolCall').map(p => ({ id: p.id, type: 'function', function: { name: p.name, arguments: JSON.stringify(p.arguments) } }));
      const row = { role: 'assistant', content: content || null };
      const reasoning = m.content.filter(p => p.type === 'thinking').map(p => p.thinking).join('');
      if (reasoning) row.reasoning_content = reasoning;
      if (tool_calls.length) row.tool_calls = tool_calls;
      messages.push(row);
    } else throw Error('unsupported Pi transcript role');
  }
  const tools = getCurrentTools(context.messages).map(t => ({ type: 'function', function: { name: t.name, description: t.description, parameters: t.parameters } }));
  return { messages, ...(tools.length ? { tools } : {}) };
}
function usage(wire) {
  const u = wire || {};
  return { input: u.prompt_tokens || 0, output: u.completion_tokens || 0, cacheRead: 0, cacheWrite: 0,
    totalTokens: u.total_tokens || (u.prompt_tokens || 0) + (u.completion_tokens || 0),
    // OpenAI completion wire has no portable cost fields; these are unknown, not billing receipts.
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } };
}
function stream(model, context, options) {
  const events = new AssistantMessageEventStream();
  const message = { role: 'assistant', content: [], api: 'openai-completions', provider: 'modelmux', model: model.id,
    usage: usage(null), stopReason: 'error', timestamp: Date.now() };
  try {
    options?.signal?.throwIfAborted();
    const completion = crossing('pi_completion', JSON.stringify(request(context))).completion;
    if (!Array.isArray(completion.choices) || completion.choices.length !== 1) throw Error('exactly one completion choice is required');
    const choice = completion.choices[0];
    const wire = choice.message;
    if (wire.role !== 'assistant') throw Error('assistant completion required');
    if (wire.content != null && typeof wire.content !== 'string') throw Error('only text completions are supported');
    if (typeof wire.reasoning_content === 'string' && wire.reasoning_content) message.content.push({ type: 'thinking', thinking: wire.reasoning_content });
    if (wire.content) message.content.push({ type: 'text', text: wire.content });
    for (const call of wire.tool_calls || []) {
      if (call.type !== 'function' || typeof call.id !== 'string') throw Error('invalid function tool call');
      message.content.push({ type: 'toolCall', id: call.id, name: call.function.name, arguments: JSON.parse(call.function.arguments) });
    }
    const reasons = { stop: 'stop', tool_calls: 'toolUse', length: 'maxTokens' };
    if (!reasons[choice.finish_reason]) throw Error('unsupported completion finish reason');
    message.stopReason = reasons[choice.finish_reason];
    message.usage = usage(completion.usage);
    events.push({ type: 'done', reason: message.stopReason, message });
    events.end(message);
  } catch (error) {
    message.content = [];
    message.stopReason = options?.signal?.aborted ? 'aborted' : 'error';
    message.errorMessage = String(error.message || error);
    events.push({ type: 'error', reason: message.stopReason, error: message });
    events.end(message);
  }
  return events;
}
let agent;
let result;
globalThis.piRuntimeInfo = function() {
  return JSON.stringify({
    runtime: 'GraalJS', transport: 'ModelMux', initialized: !!agent,
    model: agent?.state.model?.id ?? null,
    tools: agent ? agent.state.tools.map(tool => tool.name) : [],
    buffered: true,
  });
};
globalThis.piInit = function(modelId, definitionsJson) {
  if (agent) throw Error('Pi is already initialized');
  const tools = JSON.parse(definitionsJson).map(definition => ({
    ...definition, label: definition.name, executionMode: 'sequential',
    execute: async (_id, args) => {
      const reply = crossing(['read', 'edit'].includes(definition.name) ? 'pi_workspace' : 'pi_breadcrumb', definition.name, JSON.stringify(args));
      const body = JSON.parse(reply.content);
      return { content: [{ type: 'text', text: reply.content }], details: {}, isError: !!body.error };
    },
  }));
  agent = new Agent({ initialState: { model: { id: modelId, name: modelId, api: 'openai-completions', provider: 'modelmux', baseUrl: '', reasoning: false, input: ['text'], cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, contextWindow: 0, maxTokens: 0 }, tools }, streamFn: stream, toolExecution: 'sequential' });
  return true;
};
globalThis.piPrompt = function(prompt) {
  if (!agent) throw Error('Pi is not initialized');
  result = undefined;
  agent.prompt(prompt).then(() => {
    result = JSON.stringify({ messages: agent.state.messages, buffered: true });
  }, error => { result = JSON.stringify({ error: String(error.message || error) }); });
  return true;
};
globalThis.piResult = function() { return result ?? null; };
