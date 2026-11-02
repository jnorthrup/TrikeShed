// Test fixture only. Runs the genuine pinned upstream Agent, with no model or host transport.
import { Agent } from '../references/pi-mono/packages/agent/src/agent.ts';
import { AssistantMessageEventStream } from '../references/pi-mono/packages/ai/src/utils/event-stream.ts';

globalThis.piFixtureStart = function(tool: boolean) {
  globalThis.piFixtureResult = undefined;
  let calls = 0;
  let recalls = 0;
  const events: string[] = [];
  const agent = new Agent({
    initialState: { tools: tool ? [{
      name: 'recall', label: 'Fixture recall', description: 'Read fixture evidence',
      parameters: { type: 'object', properties: { query: { type: 'string' } }, required: ['query'], additionalProperties: false },
      execute: async (_id, params) => {
        if (params.query !== 'probe') throw Error('unexpected recall query');
        recalls++;
        return { content: [{ type: 'text', text: 'fixture evidence' }], details: {} };
      },
    }] : [] },
    streamFn: () => {
      calls++;
      const s = new AssistantMessageEventStream();
      const toolTurn = tool && calls === 1;
      const message = {
        role: 'assistant',
        content: toolTurn ? [{ type: 'toolCall', id: 'fixture-call', name: 'recall', arguments: { query: 'probe' } }]
          : [{ type: 'text', text: 'fixture reply' }],
        api: 'fixture', provider: 'fixture', model: 'fixture',
        usage: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0,
          cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } },
        stopReason: toolTurn ? 'toolUse' : 'stop', timestamp: Date.now(),
      };
      s.push({ type: 'done', reason: message.stopReason, message }); s.end(message); return s;
    },
  });
  agent.subscribe(event => { events.push(event.type); });
  agent.prompt('probe').then(() => {
    globalThis.piFixtureResult = JSON.stringify({ messages: agent.state.messages, calls, recalls, events });
  }, error => {
    globalThis.piFixtureResult = JSON.stringify({ error: String(error.stack || error) });
  });
  return 'admitted';
};
