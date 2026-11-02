/******/ (() => { // webpackBootstrap
/******/ 	"use strict";
/******/ 	var __webpack_modules__ = ([
/* 0 */,
/* 1 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   Agent: () => (/* binding */ Agent)
/* harmony export */ });
/* harmony import */ var _earendil_works_pi_ai__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(2);
/* harmony import */ var _agent_loop_ts__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(47);
/* harmony import */ var _stream_fn_ts__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(48);



             
	                     
	                    
	             
	           
	                
	                    
	             
	           
	          
	                      
	                     
	           
	                       
	               
	          
	         
	                  
                    

                                            

function defaultConvertToLlm(messages                )            {
	return messages.filter(
		(message) =>
			message.role === "system" ||
			message.role === "user" ||
			message.role === "assistant" ||
			message.role === "toolResult",
	);
}

const EMPTY_USAGE = {
	input: 0,
	output: 0,
	cacheRead: 0,
	cacheWrite: 0,
	totalTokens: 0,
	cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
};

const DEFAULT_MODEL = {
	id: "unknown",
	name: "unknown",
	api: "unknown",
	provider: "unknown",
	baseUrl: "",
	reasoning: false,
	input: [],
	cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
	contextWindow: 0,
	maxTokens: 0,
}                     ;

                                                                                                                       
	                     
	                                
	                              
	                      
  

/** Initial state for {@link Agent}. `systemPrompt` and `tools` become the leading system message unless `messages` already starts with one. */
                                        
	                                                                                          
  

function createMutableAgentState(initialState                    )                    {
	let tools = initialState?.tools?.slice() ?? [];
	let messages = initialState?.messages?.slice() ?? [];
	const initialMessage = (0,_earendil_works_pi_ai__WEBPACK_IMPORTED_MODULE_0__.createInitialSystemMessage)(initialState?.systemPrompt, tools.map(_earendil_works_pi_ai__WEBPACK_IMPORTED_MODULE_0__.toToolDeclaration));
	if (messages[0]?.role !== "system" && initialMessage) messages.unshift(initialMessage);

	return {
		get systemPrompt() {
			return (0,_earendil_works_pi_ai__WEBPACK_IMPORTED_MODULE_0__.getCurrentSystemPrompt)(messages);
		},
		model: initialState?.model ?? DEFAULT_MODEL,
		thinkingLevel: initialState?.thinkingLevel ?? "off",
		get tools() {
			return tools;
		},
		set tools(nextTools                  ) {
			tools = nextTools.slice();
		},
		get messages() {
			return messages;
		},
		set messages(nextMessages                ) {
			messages = nextMessages.slice();
		},
		isStreaming: false,
		streamingMessage: undefined,
		pendingToolCalls: new Set        (),
		errorMessage: undefined,
	};
}

/** Options for constructing an {@link Agent}. */
                               
	                                 
	                                                                            
	                                                                                               
	                   
	                                                                                   
	                                             
	                                               
	                                                                     
	                                                                                                                     
	                                                                                                                  
	                        
	                                
	                   
		                     
	                                                                                
	                              
		                                
		                     
	                                                                                
	                         
	                         
	                   
	                                  
	                      
	                         
	                                  
	   
	                                                                                                  
	                                                                                                    
	   
	                          
 

class PendingMessageQueue {
	        messages                 = [];
	       mode           ;

	constructor(mode           ) {
		this.mode = mode;
	}

	enqueue(message              )       {
		this.messages.push(message);
	}

	hasItems()          {
		return this.messages.length > 0;
	}

	peek()                 {
		if (this.mode === "all") return this.messages.slice();
		const first = this.messages[0];
		return first ? [first] : [];
	}

	drain()                 {
		const drained = this.peek();
		this.messages = this.messages.slice(drained.length);
		return drained;
	}

	clear()       {
		this.messages = [];
	}
}

                  
	                       
	                    
	                                 
  

/**
 * Stateful wrapper around the low-level agent loop.
 *
 * `Agent` owns the current transcript, emits lifecycle events, executes tools,
 * and exposes queueing APIs for steering and follow-up messages.
 */
class Agent {
	        _state                   ;
	                 listeners = new Set                                                                  ();
	                 steeringQueue                     ;
	                 followUpQueue                     ;

	       convertToLlm                                                              ;
	       transformContext                                                                              ;
	       streamFunction          ;
	       getApiKey                                                                         ;
	       onPayload                                   ;
	       onResponse                                    ;
	       onProviderStreamEvent                                               ;
	       beforeToolCall    
		                               
		                     
	                                              ;
	       afterToolCall    
		                              
		                     
	                                             ;
	       finishTurn             ;
	       prepareRequest                 ;
	       prepareNextTurn    
		                     
	                                                                               ;
	       prepareNextTurnWithContext    
		                                
		                     
	                                                                               ;
	        activeRun            ;
	/** Session identifier forwarded to providers for cache-aware backends. */
	       sessionId         ;
	/** Optional per-level thinking token budgets forwarded to the stream function. */
	       thinkingBudgets                  ;
	/** Preferred transport forwarded to the stream function. */
	       transport           ;
	/** Optional cap for provider-requested retry delays. */
	       maxRetryDelayMs         ;
	/** Tool execution strategy for assistant messages that contain multiple tool calls. */
	       toolExecution                   ;
	/** Request identity forwarded to the stream function, over `DEFAULT_AGENT_IDENTITY`. */
	       identity                 ;

	constructor(options              ) {
		// Older compiled consumers may omit options or streamFn even though the current API requires them.
		const runtimeOptions                        = options ?? {};
		this._state = createMutableAgentState(runtimeOptions.initialState);
		this.convertToLlm = runtimeOptions.convertToLlm ?? defaultConvertToLlm;
		this.transformContext = runtimeOptions.transformContext;
		this.streamFunction = runtimeOptions.streamFn ?? (0,_stream_fn_ts__WEBPACK_IMPORTED_MODULE_2__.getDefaultStreamFn)();
		this.getApiKey = runtimeOptions.getApiKey;
		this.onPayload = runtimeOptions.onPayload;
		this.onResponse = runtimeOptions.onResponse;
		this.onProviderStreamEvent = runtimeOptions.onProviderStreamEvent;
		this.beforeToolCall = runtimeOptions.beforeToolCall;
		this.afterToolCall = runtimeOptions.afterToolCall;
		this.finishTurn = runtimeOptions.finishTurn;
		this.prepareRequest = runtimeOptions.prepareRequest;
		this.prepareNextTurn = runtimeOptions.prepareNextTurn;
		this.prepareNextTurnWithContext = runtimeOptions.prepareNextTurnWithContext;
		this.steeringQueue = new PendingMessageQueue(runtimeOptions.steeringMode ?? "one-at-a-time");
		this.followUpQueue = new PendingMessageQueue(runtimeOptions.followUpMode ?? "one-at-a-time");
		this.sessionId = runtimeOptions.sessionId;
		this.thinkingBudgets = runtimeOptions.thinkingBudgets;
		this.transport = runtimeOptions.transport ?? "auto";
		this.maxRetryDelayMs = runtimeOptions.maxRetryDelayMs;
		this.toolExecution = runtimeOptions.toolExecution ?? "parallel";
		this.identity = runtimeOptions.identity;
	}

	/**
	 * Subscribe to agent lifecycle events.
	 *
	 * Listener promises are awaited in subscription order and are included in
	 * the current run's settlement. Listeners also receive the active abort
	 * signal for the current run.
	 *
	 * `agent_end` is the final emitted event for a run, but the agent does not
	 * become idle until all awaited listeners for that event have settled.
	 */
	subscribe(listener                                                                  )             {
		this.listeners.add(listener);
		return () => this.listeners.delete(listener);
	}

	/**
	 * Current agent state.
	 *
	 * Assigning `state.tools` or `state.messages` copies the provided top-level array.
	 */
	get state()             {
		return this._state;
	}

	/** Controls how queued steering messages are drained. */
	set steeringMode(mode           ) {
		this.steeringQueue.mode = mode;
	}

	get steeringMode()            {
		return this.steeringQueue.mode;
	}

	/** Controls how queued follow-up messages are drained. */
	set followUpMode(mode           ) {
		this.followUpQueue.mode = mode;
	}

	get followUpMode()            {
		return this.followUpQueue.mode;
	}

	/** Queue a message to be injected after the current assistant turn finishes. */
	steer(message              )       {
		this.steeringQueue.enqueue(message);
	}

	/** Queue a message to run only after the agent would otherwise stop. */
	followUp(message              )       {
		this.followUpQueue.enqueue(message);
	}

	/** Remove all queued steering messages. */
	clearSteeringQueue()       {
		this.steeringQueue.clear();
	}

	/** Remove all queued follow-up messages. */
	clearFollowUpQueue()       {
		this.followUpQueue.clear();
	}

	/** Remove all queued steering and follow-up messages. */
	clearAllQueues()       {
		this.clearSteeringQueue();
		this.clearFollowUpQueue();
	}

	/** Returns true when either queue still contains pending messages. */
	hasQueuedMessages()          {
		return this.steeringQueue.hasItems() || this.followUpQueue.hasItems();
	}

	/** Preview the messages selected for the next turn without consuming them. */
	peekQueuedMessages()                 {
		const steering = this.steeringQueue.peek();
		return steering.length > 0 ? steering : this.followUpQueue.peek();
	}

	/** Active abort signal for the current run, if any. */
	get signal()                          {
		return this.activeRun?.abortController.signal;
	}

	/** Abort the current run, if one is active. */
	abort()       {
		this.activeRun?.abortController.abort();
	}

	/**
	 * Resolve when the current run and all awaited event listeners have finished.
	 *
	 * This resolves after `agent_end` listeners settle.
	 */
	waitForIdle()                {
		return this.activeRun?.promise ?? Promise.resolve();
	}

	/** Clear conversation state and queues while retaining the replayed prompt/tool baseline. */
	reset()       {
		if (this.activeRun) {
			throw new Error("Agent is already processing. Wait for completion before resetting.");
		}

		const baseline = (0,_earendil_works_pi_ai__WEBPACK_IMPORTED_MODULE_0__.getCurrentSystemMessage)(this._state.messages);
		this._state.messages = baseline ? [baseline] : [];
		this._state.isStreaming = false;
		this._state.streamingMessage = undefined;
		this._state.pendingToolCalls = new Set        ();
		this._state.errorMessage = undefined;
		this.clearFollowUpQueue();
		this.clearSteeringQueue();
	}

	/** Start a new prompt from text, a single message, or a batch of messages. */
	                                                                    
	                                                                    
	async prompt(input                                        , images                 )                {
		if (this.activeRun) {
			throw new Error(
				"Agent is already processing a prompt. Use steer() or followUp() to queue messages, or wait for completion.",
			);
		}
		const messages = this.normalizePromptInput(input, images);
		await this.runPromptMessages(messages);
	}

	/** Continue from the current transcript. The last message must be a user or tool-result message. */
	async continue()                {
		if (this.activeRun) {
			throw new Error("Agent is already processing. Wait for completion before continuing.");
		}

		const lastMessage = this._state.messages[this._state.messages.length - 1];
		if (!lastMessage || this._state.messages.every((message) => message.role === "system")) {
			throw new Error("No messages to continue from");
		}

		if (lastMessage.role === "assistant") {
			const queuedSteering = this.steeringQueue.drain();
			if (queuedSteering.length > 0) {
				await this.runPromptMessages(queuedSteering, { skipInitialSteeringPoll: true });
				return;
			}

			const queuedFollowUps = this.followUpQueue.drain();
			if (queuedFollowUps.length > 0) {
				await this.runPromptMessages(queuedFollowUps);
				return;
			}

			throw new Error("Cannot continue from message role: assistant");
		}

		await this.runContinuation();
	}

	        normalizePromptInput(
		input                                        ,
		images                 ,
	)                 {
		if (Array.isArray(input)) {
			return input;
		}

		if (typeof input !== "string") {
			return [input];
		}

		const content                                    = [{ type: "text", text: input }];
		if (images && images.length > 0) {
			content.push(...images);
		}
		return [{ role: "user", content, timestamp: Date.now() }];
	}

	        async runPromptMessages(
		messages                ,
		options                                        = {},
	)                {
		await this.runWithLifecycle(async (signal) => {
			await (0,_agent_loop_ts__WEBPACK_IMPORTED_MODULE_1__.runAgentLoop)(
				messages,
				this.createContextSnapshot(),
				this.createLoopConfig(options),
				(event) => this.processEvents(event),
				signal,
				this.streamFunction,
			);
		});
	}

	        async runContinuation()                {
		await this.runWithLifecycle(async (signal) => {
			await (0,_agent_loop_ts__WEBPACK_IMPORTED_MODULE_1__.runAgentLoopContinue)(
				this.createContextSnapshot(),
				this.createLoopConfig(),
				(event) => this.processEvents(event),
				signal,
				this.streamFunction,
			);
		});
	}

	        createContextSnapshot()               {
		return {
			messages: this._state.messages.slice(),
			tools: this._state.tools.slice(),
		};
	}

	        createLoopConfig(options                                        = {})                  {
		let skipInitialSteeringPoll = options.skipInitialSteeringPoll === true;
		return {
			model: this._state.model,
			reasoning: this._state.thinkingLevel === "off" ? undefined : this._state.thinkingLevel,
			sessionId: this.sessionId,
			onPayload: this.onPayload,
			onResponse: this.onResponse,
			onProviderStreamEvent: this.onProviderStreamEvent,
			transport: this.transport,
			thinkingBudgets: this.thinkingBudgets,
			maxRetryDelayMs: this.maxRetryDelayMs,
			identity: this.identity,
			toolExecution: this.toolExecution,
			beforeToolCall: this.beforeToolCall,
			afterToolCall: this.afterToolCall,
			finishTurn: this.finishTurn,
			prepareRequest: this.prepareRequest,
			prepareNextTurn:
				this.prepareNextTurnWithContext || this.prepareNextTurn
					? async (context) => {
							if (this.prepareNextTurnWithContext) {
								return await this.prepareNextTurnWithContext(context, this.signal);
							}
							return await this.prepareNextTurn?.(this.signal);
						}
					: undefined,
			convertToLlm: this.convertToLlm,
			transformContext: this.transformContext,
			getApiKey: this.getApiKey,
			getSteeringMessages: async () => {
				if (skipInitialSteeringPoll) {
					skipInitialSteeringPoll = false;
					return [];
				}
				return this.steeringQueue.drain();
			},
			getFollowUpMessages: async () => this.followUpQueue.drain(),
		};
	}

	        async runWithLifecycle(executor                                        )                {
		if (this.activeRun) {
			throw new Error("Agent is already processing.");
		}

		const abortController = new AbortController();
		let resolvePromise = () => {};
		const promise = new Promise      ((resolve) => {
			resolvePromise = resolve;
		});
		this.activeRun = { promise, resolve: resolvePromise, abortController };

		this._state.isStreaming = true;
		this._state.streamingMessage = undefined;
		this._state.errorMessage = undefined;

		try {
			await executor(abortController.signal);
		} catch (error) {
			await this.handleRunFailure(error, abortController.signal.aborted);
		} finally {
			this.finishRun();
		}
	}

	        async handleRunFailure(error         , aborted         )                {
		const failureMessage = {
			role: "assistant",
			content: [{ type: "text", text: "" }],
			api: this._state.model.api,
			provider: this._state.model.provider,
			model: this._state.model.id,
			usage: EMPTY_USAGE,
			stopReason: aborted ? "aborted" : "error",
			errorMessage: error instanceof Error ? error.message : String(error),
			timestamp: Date.now(),
		}                       ;
		await this.processEvents({ type: "message_start", message: failureMessage });
		await this.processEvents({ type: "message_end", message: failureMessage });
		await this.processEvents({ type: "turn_end", message: failureMessage, toolResults: [] });
		await this.processEvents({ type: "agent_end", messages: [failureMessage] });
	}

	        finishRun()       {
		this._state.isStreaming = false;
		this._state.streamingMessage = undefined;
		this._state.pendingToolCalls = new Set        ();
		this.activeRun?.resolve();
		this.activeRun = undefined;
	}

	/**
	 * Reduce internal state for a loop event, then await listeners.
	 *
	 * `agent_end` only means no further loop events will be emitted. The run is
	 * considered idle later, after all awaited listeners for `agent_end` finish
	 * and `finishRun()` clears runtime-owned state.
	 */
	        async processEvents(event            )                {
		switch (event.type) {
			case "message_start":
				this._state.streamingMessage = event.message;
				break;

			case "message_update":
				this._state.streamingMessage = event.message;
				break;

			case "message_end":
				this._state.streamingMessage = undefined;
				this._state.messages.push(event.message);
				break;

			case "tool_execution_start": {
				const pendingToolCalls = new Set(this._state.pendingToolCalls);
				pendingToolCalls.add(event.toolCallId);
				this._state.pendingToolCalls = pendingToolCalls;
				break;
			}

			case "tool_execution_end": {
				const pendingToolCalls = new Set(this._state.pendingToolCalls);
				pendingToolCalls.delete(event.toolCallId);
				this._state.pendingToolCalls = pendingToolCalls;
				break;
			}

			case "turn_end":
				if (event.message.role === "assistant" && event.message.errorMessage) {
					this._state.errorMessage = event.message.errorMessage;
				}
				break;

			case "agent_end":
				this._state.streamingMessage = undefined;
				break;
		}

		const signal = this.activeRun?.abortController.signal;
		if (!signal) {
			throw new Error("Agent listener invoked outside active run");
		}
		for (const listener of this.listeners) {
			await listener(event, signal);
		}
	}
}


/***/ }),
/* 2 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   AssistantMessageEventStream: () => (/* reexport safe */ _references_pi_mono_packages_ai_src_utils_event_stream_ts__WEBPACK_IMPORTED_MODULE_1__.AssistantMessageEventStream),
/* harmony export */   EventStream: () => (/* reexport safe */ _references_pi_mono_packages_ai_src_utils_event_stream_ts__WEBPACK_IMPORTED_MODULE_1__.EventStream),
/* harmony export */   PI_ATTRIBUTION: () => (/* reexport safe */ _references_pi_mono_packages_ai_src_utils_identity_ts__WEBPACK_IMPORTED_MODULE_2__.PI_ATTRIBUTION),
/* harmony export */   collapseSystemMessages: () => (/* reexport safe */ _references_pi_mono_packages_ai_src_utils_transcript_ts__WEBPACK_IMPORTED_MODULE_0__.collapseSystemMessages),
/* harmony export */   createAssistantMessageEventStream: () => (/* reexport safe */ _references_pi_mono_packages_ai_src_utils_event_stream_ts__WEBPACK_IMPORTED_MODULE_1__.createAssistantMessageEventStream),
/* harmony export */   createInitialSystemMessage: () => (/* reexport safe */ _references_pi_mono_packages_ai_src_utils_transcript_ts__WEBPACK_IMPORTED_MODULE_0__.createInitialSystemMessage),
/* harmony export */   declarationsEqual: () => (/* reexport safe */ _references_pi_mono_packages_ai_src_utils_transcript_ts__WEBPACK_IMPORTED_MODULE_0__.declarationsEqual),
/* harmony export */   formatUserAgent: () => (/* reexport safe */ _references_pi_mono_packages_ai_src_utils_identity_ts__WEBPACK_IMPORTED_MODULE_2__.formatUserAgent),
/* harmony export */   getAttributionHeaders: () => (/* reexport safe */ _references_pi_mono_packages_ai_src_utils_identity_ts__WEBPACK_IMPORTED_MODULE_2__.getAttributionHeaders),
/* harmony export */   getClientVersion: () => (/* reexport safe */ _references_pi_mono_packages_ai_src_utils_identity_ts__WEBPACK_IMPORTED_MODULE_2__.getClientVersion),
/* harmony export */   getCurrentSystemMessage: () => (/* reexport safe */ _references_pi_mono_packages_ai_src_utils_transcript_ts__WEBPACK_IMPORTED_MODULE_0__.getCurrentSystemMessage),
/* harmony export */   getCurrentSystemPrompt: () => (/* reexport safe */ _references_pi_mono_packages_ai_src_utils_transcript_ts__WEBPACK_IMPORTED_MODULE_0__.getCurrentSystemPrompt),
/* harmony export */   getCurrentTools: () => (/* reexport safe */ _references_pi_mono_packages_ai_src_utils_transcript_ts__WEBPACK_IMPORTED_MODULE_0__.getCurrentTools),
/* harmony export */   getDeclaredTools: () => (/* reexport safe */ _references_pi_mono_packages_ai_src_utils_transcript_ts__WEBPACK_IMPORTED_MODULE_0__.getDeclaredTools),
/* harmony export */   getIdentityHeaders: () => (/* reexport safe */ _references_pi_mono_packages_ai_src_utils_identity_ts__WEBPACK_IMPORTED_MODULE_2__.getIdentityHeaders),
/* harmony export */   getInitialSystemMessage: () => (/* reexport safe */ _references_pi_mono_packages_ai_src_utils_transcript_ts__WEBPACK_IMPORTED_MODULE_0__.getInitialSystemMessage),
/* harmony export */   getOpenCodeSessionHeaders: () => (/* reexport safe */ _references_pi_mono_packages_ai_src_utils_identity_ts__WEBPACK_IMPORTED_MODULE_2__.getOpenCodeSessionHeaders),
/* harmony export */   getToolStateChanges: () => (/* reexport safe */ _references_pi_mono_packages_ai_src_utils_transcript_ts__WEBPACK_IMPORTED_MODULE_0__.getToolStateChanges),
/* harmony export */   hasNonAdditiveToolChanges: () => (/* reexport safe */ _references_pi_mono_packages_ai_src_utils_transcript_ts__WEBPACK_IMPORTED_MODULE_0__.hasNonAdditiveToolChanges),
/* harmony export */   hasToolRedefinitions: () => (/* reexport safe */ _references_pi_mono_packages_ai_src_utils_transcript_ts__WEBPACK_IMPORTED_MODULE_0__.hasToolRedefinitions),
/* harmony export */   mergeClientIdentity: () => (/* reexport safe */ _references_pi_mono_packages_ai_src_utils_identity_ts__WEBPACK_IMPORTED_MODULE_2__.mergeClientIdentity),
/* harmony export */   normalizeContext: () => (/* reexport safe */ _references_pi_mono_packages_ai_src_utils_transcript_ts__WEBPACK_IMPORTED_MODULE_0__.normalizeContext),
/* harmony export */   resolveTranscript: () => (/* reexport safe */ _references_pi_mono_packages_ai_src_utils_transcript_ts__WEBPACK_IMPORTED_MODULE_0__.resolveTranscript),
/* harmony export */   resolveTranscriptTools: () => (/* reexport safe */ _references_pi_mono_packages_ai_src_utils_transcript_ts__WEBPACK_IMPORTED_MODULE_0__.resolveTranscriptTools),
/* harmony export */   toToolDeclaration: () => (/* reexport safe */ _references_pi_mono_packages_ai_src_utils_transcript_ts__WEBPACK_IMPORTED_MODULE_0__.toToolDeclaration),
/* harmony export */   validateToolArguments: () => (/* reexport safe */ _references_pi_mono_packages_ai_src_utils_validation_ts__WEBPACK_IMPORTED_MODULE_3__.validateToolArguments),
/* harmony export */   validateToolCall: () => (/* reexport safe */ _references_pi_mono_packages_ai_src_utils_validation_ts__WEBPACK_IMPORTED_MODULE_3__.validateToolCall),
/* harmony export */   withClientIdentity: () => (/* reexport safe */ _references_pi_mono_packages_ai_src_utils_identity_ts__WEBPACK_IMPORTED_MODULE_2__.withClientIdentity),
/* harmony export */   withoutInitialSystemMessage: () => (/* reexport safe */ _references_pi_mono_packages_ai_src_utils_transcript_ts__WEBPACK_IMPORTED_MODULE_0__.withoutInitialSystemMessage)
/* harmony export */ });
/* harmony import */ var _references_pi_mono_packages_ai_src_utils_transcript_ts__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(3);
/* harmony import */ var _references_pi_mono_packages_ai_src_utils_event_stream_ts__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(5);
/* harmony import */ var _references_pi_mono_packages_ai_src_utils_identity_ts__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(6);
/* harmony import */ var _references_pi_mono_packages_ai_src_utils_validation_ts__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(8);






/***/ }),
/* 3 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   collapseSystemMessages: () => (/* binding */ collapseSystemMessages),
/* harmony export */   createInitialSystemMessage: () => (/* binding */ createInitialSystemMessage),
/* harmony export */   declarationsEqual: () => (/* binding */ declarationsEqual),
/* harmony export */   getCurrentSystemMessage: () => (/* binding */ getCurrentSystemMessage),
/* harmony export */   getCurrentSystemPrompt: () => (/* binding */ getCurrentSystemPrompt),
/* harmony export */   getCurrentTools: () => (/* binding */ getCurrentTools),
/* harmony export */   getDeclaredTools: () => (/* binding */ getDeclaredTools),
/* harmony export */   getInitialSystemMessage: () => (/* binding */ getInitialSystemMessage),
/* harmony export */   getToolStateChanges: () => (/* binding */ getToolStateChanges),
/* harmony export */   hasNonAdditiveToolChanges: () => (/* binding */ hasNonAdditiveToolChanges),
/* harmony export */   hasToolRedefinitions: () => (/* binding */ hasToolRedefinitions),
/* harmony export */   normalizeContext: () => (/* binding */ normalizeContext),
/* harmony export */   resolveTranscript: () => (/* binding */ resolveTranscript),
/* harmony export */   resolveTranscriptTools: () => (/* binding */ resolveTranscriptTools),
/* harmony export */   toToolDeclaration: () => (/* binding */ toToolDeclaration),
/* harmony export */   withoutInitialSystemMessage: () => (/* binding */ withoutInitialSystemMessage)
/* harmony export */ });
/* harmony import */ var _text_ts__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(4);
                                                                                                           


                                                     

/**
 * Build the leading system message for a prompt and tool set. Returns undefined when
 * both are empty, so an empty transcript stays empty.
 */
function createInitialSystemMessage(
	systemPrompt                    ,
	tools                    ,
)                            {
	const hasSystemPrompt = systemPrompt !== undefined && systemPrompt.length > 0;
	const hasTools = tools !== undefined && tools.length > 0;
	if (!hasSystemPrompt && !hasTools) return undefined;
	return {
		role: "system",
		content: systemPrompt ?? "",
		...(hasTools ? { toolsAdded: tools } : {}),
		timestamp: 0,
	};
}

/**
 * Fold `Context.systemPrompt` and `Context.tools` into a leading system message.
 * This is the only entry point that produces a {@link TranscriptContext}; every
 * provider-facing function expects the result.
 */
function normalizeContext(context         )                    {
	const initialMessage = createInitialSystemMessage(context.systemPrompt, context.tools);
	const messages = initialMessage ? [initialMessage, ...context.messages] : context.messages;
	return { messages }                     ;
}

/**
 * Any message list. The replay helpers only read entries whose role is `"system"`, so
 * agent transcripts that carry custom message roles can be passed without filtering.
 */
                                                             

function isSystemMessage(message                  )                           {
	return message.role === "system";
}

/** Return the leading system message, if the transcript starts with one. */
function getInitialSystemMessage(messages                    )                            {
	const first = messages[0];
	return first && isSystemMessage(first) ? first : undefined;
}

/** Drop the leading system message for APIs that carry the prompt outside the message list. */
function withoutInitialSystemMessage(messages           )            {
	return getInitialSystemMessage(messages) ? messages.slice(1) : messages;
}

/** Resolve the tools available after applying every transcript delta in order. */
function getCurrentTools(messages                    )         {
	const tools = new Map              ();
	for (const message of messages) {
		if (!isSystemMessage(message)) continue;
		for (const tool of message.toolsRemoved ?? []) tools.delete(tool.name);
		for (const tool of message.toolsAdded ?? []) tools.set(tool.name, tool);
	}
	return [...tools.values()];
}

/**
 * Replay every system message into one leading system message holding the current
 * prompt and tools. Later `content` is appended to the base prompt, `sections` are
 * patched by name, and tools are resolved with {@link getCurrentTools}.
 */
function getCurrentSystemMessage(messages                    )                            {
	const content           = [];
	const sections = new Map                ();
	let timestamp                    ;
	for (const message of messages) {
		if (!isSystemMessage(message)) continue;
		timestamp ??= message.timestamp;
		const text = (0,_text_ts__WEBPACK_IMPORTED_MODULE_0__.contentText)(message.content);
		if (text.length > 0) content.push(text);
		for (const [name, value] of Object.entries(message.sections ?? {})) {
			if (value === null) sections.delete(name);
			else sections.set(name, value);
		}
	}
	const tools = getCurrentTools(messages);
	if (timestamp === undefined && tools.length === 0) return undefined;
	return {
		role: "system",
		content: content.join("\n\n"),
		...(sections.size > 0 ? { sections: Object.fromEntries(sections) } : {}),
		...(tools.length > 0 ? { toolsAdded: tools } : {}),
		timestamp: timestamp ?? 0,
	};
}

/** Render the current system prompt text after replaying every system message. */
function getCurrentSystemPrompt(messages                    )         {
	const message = getCurrentSystemMessage(messages);
	return message ? (0,_text_ts__WEBPACK_IMPORTED_MODULE_0__.getSystemMessageText)(message) : "";
}

/**
 * Rebuild the transcript for APIs without mid-conversation system messages: the replayed
 * system message leads, and every later system message is dropped.
 */
function collapseSystemMessages(context                   )                    {
	const head = getCurrentSystemMessage(context.messages);
	const messages = context.messages.filter((message) => message.role !== "system");
	return { messages: head ? [head, ...messages] : messages }                     ;
}

/** Keep later system messages in place when the model accepts them; otherwise collapse them. */
function resolveTranscript(
	context                   ,
	supportsMidConvoSystemMessages                     ,
)                    {
	return supportsMidConvoSystemMessages ? context : collapseSystemMessages(context);
}

/** Strip executable and display-only fields from a tool before transcript comparison or persistence. */
function toToolDeclaration(tool      )       {
	return {
		name: tool.name,
		description: tool.description,
		parameters: JSON.parse(JSON.stringify(tool.parameters))                      ,
		...(tool.constrainedSampling === undefined ? {} : { constrainedSampling: tool.constrainedSampling }),
	};
}

/**
 * Whether two tools declare the same interface to the model.
 *
 * Both sides go through {@link toToolDeclaration} first: its JSON round-trip drops the
 * schema metadata keys (TypeBox's non-enumerable kind marks) and `undefined` fields that a structural comparison would see, and
 * builds both objects with the same key order, so comparing the serialized declarations
 * is exact. This avoids a deep-equal dependency in a browser-safe package.
 */
function declarationsEqual(left      , right      )          {
	return JSON.stringify(toToolDeclaration(left)) === JSON.stringify(toToolDeclaration(right));
}

                                   
	                   
	                              
 

/** Compare two complete tool states. A changed definition is a removal followed by an addition. */
function getToolStateChanges(previous                 , current                 )                   {
	const previousTools = new Map(previous.map((tool) => [tool.name, tool]));
	const currentTools = new Map(current.map((tool) => [tool.name, tool]));
	return {
		toolsAdded: current
			.filter((tool) => {
				const previousTool = previousTools.get(tool.name);
				return previousTool === undefined || !declarationsEqual(previousTool, tool);
			})
			.map(toToolDeclaration),
		toolsRemoved: previous
			.filter((tool) => {
				const currentTool = currentTools.get(tool.name);
				return currentTool === undefined || !declarationsEqual(tool, currentTool);
			})
			.map((tool) => ({ name: tool.name })),
	};
}

/** Every definition referenced by transcript tool state, in first-declaration order. */
function getDeclaredTools(messages                    )         {
	const definitions = new Map              ();
	for (const message of messages) {
		if (!isSystemMessage(message)) continue;
		for (const tool of message.toolsAdded ?? []) definitions.set(tool.name, tool);
	}
	return [...definitions.values()];
}

/**
 * Whether a tool name was declared twice with different definitions. A transport that can
 * only reference previously declared tools by name cannot replay such a history.
 *
 * @deprecated No built-in transport needs this anymore: Anthropic expresses redefinitions with
 * inline `tool_definition` blocks. Kept for API compatibility and will be removed in a future release.
 */
function hasToolRedefinitions(messages                    )          {
	const declared = new Map              ();
	for (const message of messages) {
		if (!isSystemMessage(message)) continue;
		for (const tool of message.toolsAdded ?? []) {
			const previous = declared.get(tool.name);
			if (previous !== undefined && !declarationsEqual(previous, tool)) return true;
			declared.set(tool.name, tool);
		}
	}
	return false;
}

/** Whether tool history contains a removal or same-name redeclaration that an addition-only transport cannot replay. */
function hasNonAdditiveToolChanges(messages                    )          {
	return findLastNonAdditiveToolChange(messages.filter(isSystemMessage)) >= 0;
}

/** Index of the last system message with a removal or same-name redeclaration, or -1. */
function findLastNonAdditiveToolChange(systemMessages                          )         {
	// A name declared before counts as a redeclaration even after its removal: Kimi K3 ignores a
	// removed tool that a later tool message declares again (verified October 2026).
	const declared = new Set        ();
	let last = -1;
	for (let index = 0; index < systemMessages.length; index++) {
		const message = systemMessages[index];
		if ((message.toolsRemoved?.length ?? 0) > 0) last = index;
		for (const tool of message.toolsAdded ?? []) {
			if (declared.has(tool.name)) last = index;
			declared.add(tool.name);
		}
	}
	return last;
}

/**
 * How a transport can express tool changes after the first request.
 *
 * - `"none"`: only the top-level tool list exists; it always holds the current tools.
 * - `"additions"`: tools can be added at a later message, but not removed or redefined.
 * - `"changes"`: every tool, including the initial ones, is declared in place, and a later
 *   declaration under the same name replaces the earlier one. Removals are not expressible on
 *   the wire, so the transport announces them as text and the removed tools stay declared.
 */
                                                                     

                                  
	                                                 
	                     
	   
	                                                                                           
	                                                              
	   
	                             
 

/**
 * Split tool declarations between the top-level request field and in-place changes.
 *
 * With `"changes"`, the top-level list stays empty and every system message, including the
 * leading one, declares its tools in place: an in-place declaration can replace an earlier
 * in-place one, but not a top-level one. With `"additions"`, a removal or redefinition cannot be
 * expressed in place, so the top-level list becomes the tool state after the last such change;
 * later additions stay anchored, and the cached prefix is invalidated once per removal instead
 * of on every later addition. With `"none"`, the top-level list is the current tool set.
 */
function resolveTranscriptTools(messages                    , support                       )                  {
	const systemMessages = messages.filter(isSystemMessage);
	if (support === "none") {
		return { requestTools: getCurrentTools(systemMessages), foldedSystemMessages: systemMessages.length };
	}
	if (support === "changes") return { requestTools: [], foldedSystemMessages: 0 };
	const folded = Math.max(
		getInitialSystemMessage(messages) ? 1 : 0,
		findLastNonAdditiveToolChange(systemMessages) + 1,
	);
	return { requestTools: getCurrentTools(systemMessages.slice(0, folded)), foldedSystemMessages: folded };
}


/***/ }),
/* 4 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   contentText: () => (/* binding */ contentText),
/* harmony export */   getSystemMessageText: () => (/* binding */ getSystemMessageText),
/* harmony export */   ownedSlice: () => (/* binding */ ownedSlice),
/* harmony export */   renderSystemMessageUpdate: () => (/* binding */ renderSystemMessageUpdate)
/* harmony export */ });
                                                                                                       

                                                                       

/** Extract and join text from message content. */
function contentText(content                             , separator = "\n")         {
	if (typeof content === "string") return content;
	return content
		.filter((block) => block.type === "text")
		.map((block) => block.text)
		.join(separator);
}

/** Render a system message as a complete prompt: its content followed by its sections. */
function getSystemMessageText(message               )         {
	const parts = [contentText(message.content)];
	for (const text of Object.values(message.sections ?? {})) {
		if (text !== null) parts.push(text);
	}
	return parts.filter((part) => part.length > 0).join("\n\n");
}

/**
 * Render a later system message for APIs that accept system messages mid-conversation.
 * Section changes are framed by name so the model can relate them to the leading prompt.
 * This framing is request-time only and may change between versions.
 */
function renderSystemMessageUpdate(message               )         {
	const parts           = [];
	const text = contentText(message.content);
	if (text.length > 0) parts.push(text);
	for (const [name, value] of Object.entries(message.sections ?? {})) {
		parts.push(
			value === null
				? `Removed system prompt section "${name}".`
				: `Updated system prompt section "${name}":\n\n${value}`,
		);
	}
	return parts.join("\n\n");
}

/**
 * `text.slice(start, end)` as a string of its own. V8 represents a slice of 13 or more characters as a view that keeps
 * all of `text` alive, so a short excerpt kept from a large text (a 500-character error message cut from a megabyte
 * response body) would keep the whole text alive as long as the excerpt lives. Appending a character and slicing it off
 * again makes V8 copy just the slice.
 */
function ownedSlice(text        , start        , end = text.length)         {
	return `${text.slice(start, end)} `.slice(0, -1);
}


/***/ }),
/* 5 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   AssistantMessageEventStream: () => (/* binding */ AssistantMessageEventStream),
/* harmony export */   EventStream: () => (/* binding */ EventStream),
/* harmony export */   createAssistantMessageEventStream: () => (/* binding */ createAssistantMessageEventStream)
/* harmony export */ });
                                                                           

class FifoQueue    {
	        incoming      = [];
	        outgoing      = [];

	get length()         {
		return this.incoming.length + this.outgoing.length;
	}

	enqueue(value   )       {
		this.incoming.push(value);
	}

	dequeue()                {
		if (this.outgoing.length === 0) {
			while (this.incoming.length > 0) {
				this.outgoing.push(this.incoming.pop() );
			}
		}
		return this.outgoing.pop();
	}
}

// Generic event stream class for async iteration
class EventStream                                       {
	        queue = new FifoQueue   ();
	        waiting = new FifoQueue                                    ();
	          done = false;
	        finalResultPromise            ;
	        resolveFinalResult                      ;
	        isComplete                       ;
	        extractResult                 ;

	constructor(isComplete                       , extractResult                 ) {
		this.isComplete = isComplete;
		this.extractResult = extractResult;
		this.finalResultPromise = new Promise((resolve) => {
			this.resolveFinalResult = resolve;
		});
	}

	push(event   )       {
		if (this.done) return;

		if (this.isComplete(event)) {
			this.done = true;
			this.resolveFinalResult(this.extractResult(event));
		}

		// Deliver to waiting consumer or queue it
		const waiter = this.waiting.dequeue();
		if (waiter) {
			waiter({ value: event, done: false });
		} else {
			this.queue.enqueue(event);
		}
	}

	end(result    )       {
		this.done = true;
		if (result !== undefined) {
			this.resolveFinalResult(result);
		}
		// Notify all waiting consumers that we're done
		while (this.waiting.length > 0) {
			const waiter = this.waiting.dequeue() ;
			waiter({ value: undefined       , done: true });
		}
	}

	async *[Symbol.asyncIterator]()                   {
		while (true) {
			if (this.queue.length > 0) {
				yield this.queue.dequeue() ;
			} else if (this.done) {
				return;
			} else {
				const result = await new Promise                   ((resolve) => this.waiting.enqueue(resolve));
				if (result.done) return;
				yield result.value;
			}
		}
	}

	result()             {
		return this.finalResultPromise;
	}
}

/**
 * Event stream of one assistant response. It also times the response: the final message (`done` or `error` event, or
 * the result passed to `end()`) gets `durationMs`, measured with a monotonic clock from the stream's creation, unless
 * the message already has one or its `timestamp` predates the stream. A stream that forwards a response which started
 * elsewhere, such as a deferred result fetched later, therefore leaves it untimed.
 */
class AssistantMessageEventStream extends EventStream                                          {
	         #startedAt = Date.now();
	         #startedAtMonotonic = performance.now();

	constructor() {
		super(
			(event) => event.type === "done" || event.type === "error",
			(event) => {
				if (event.type === "done") {
					return event.message;
				} else if (event.type === "error") {
					return event.error;
				}
				throw new Error("Unexpected event type for final result");
			},
		);
	}

	         push(event                       )       {
		if (event.type === "done") this.#time(event.message);
		else if (event.type === "error") this.#time(event.error);
		super.push(event);
	}

	         end(result                   )       {
		if (result !== undefined) this.#time(result);
		super.end(result);
	}

	#time(message                  )       {
		if (this.done || message.durationMs !== undefined || message.timestamp < this.#startedAt) return;
		message.durationMs = Math.max(0, Math.round(performance.now() - this.#startedAtMonotonic));
	}
}

/** Factory function for AssistantMessageEventStream (for use in extensions) */
function createAssistantMessageEventStream()                              {
	return new AssistantMessageEventStream();
}


/***/ }),
/* 6 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   PI_ATTRIBUTION: () => (/* binding */ PI_ATTRIBUTION),
/* harmony export */   formatUserAgent: () => (/* binding */ formatUserAgent),
/* harmony export */   getAttributionHeaders: () => (/* binding */ getAttributionHeaders),
/* harmony export */   getClientVersion: () => (/* binding */ getClientVersion),
/* harmony export */   getIdentityHeaders: () => (/* binding */ getIdentityHeaders),
/* harmony export */   getOpenCodeSessionHeaders: () => (/* binding */ getOpenCodeSessionHeaders),
/* harmony export */   mergeClientIdentity: () => (/* binding */ mergeClientIdentity),
/* harmony export */   withClientIdentity: () => (/* binding */ withClientIdentity)
/* harmony export */ });
/* harmony import */ var _version_ts__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(7);
                                                          


/**
 * The `User-Agent` sent to providers:
 * - `"detailed"`: `pi/<version> (<platform>; <runtime>/<runtime version>; <arch>)`, or `pi/<version> (browser)`
 *   outside Node.js and Bun.
 * - `"short"`: `pi`.
 * - `{ custom }`: the given value.
 */
                                                                      

/** Who requests are attributed to. `getAttributionHeaders()` maps the fields to each provider's headers. */
                                    
	                                                                                                        
	             
	                                                                                                          
	            
	                                                                                                   
	                      
 

/** pi's attribution, used by pi-agent-core, pi-durable, and the coding agent. */
const PI_ATTRIBUTION                    = { name: "pi", url: "https://pi.dev", categories: ["cli-agent"] };

/**
 * How pi-ai identifies the calling app to providers. Every field is optional; absent fields use pi-ai's defaults:
 * a detailed `User-Agent` with pi-ai's version and no attribution headers. Plain JSON, so it can be persisted.
 */
                                 
	                             
	                          
	                                                                                                                        
	                                       
	                                                                        
	                 
 

/** The provider and endpoint a request goes to. */
                                       
	                          
	                         
 

const NVIDIA_NIM_HOST = "integrate.api.nvidia.com";
const OPENCODE_HOST = "opencode.ai";

/** Merge identities field by field; later sources win and undefined fields are skipped. */
function mergeClientIdentity(...sources                                )                             {
	let merged                            ;
	for (const source of sources) {
		if (!source) continue;
		merged = { ...merged };
		if (source.userAgent !== undefined) merged.userAgent = source.userAgent;
		if (source.attribution !== undefined) merged.attribution = source.attribution;
		if (source.version !== undefined) merged.version = source.version;
	}
	return merged;
}

/** The version in the detailed `User-Agent`. */
function getClientVersion(identity                 )         {
	return identity?.version ?? _version_ts__WEBPACK_IMPORTED_MODULE_0__.PI_AI_VERSION;
}

function runtimeDescription()                     {
	if (typeof process === "undefined") return undefined;
	const versions = process.versions;
	if (versions?.bun) return `bun/${versions.bun}`;
	if (versions?.node) return `node/${process.version}`;
	return undefined;
}

/** The `User-Agent` value of an identity. */
function formatUserAgent(identity                 )         {
	const mode = identity?.userAgent ?? "detailed";
	if (typeof mode === "object") return mode.custom;
	if (mode === "short") return "pi";
	const product = `pi/${getClientVersion(identity)}`;
	const runtime = runtimeDescription();
	return runtime ? `${product} (${process.platform}; ${runtime}; ${process.arch})` : `${product} (browser)`;
}

function matchesHost(baseUrl        , expectedHost        )          {
	try {
		return new URL(baseUrl).hostname === expectedHost;
	} catch {
		return false;
	}
}

function isOpenRouter(target                      )          {
	return target.provider === "openrouter" || target.baseUrl.includes("openrouter.ai");
}

function isNvidiaNim(target                      )          {
	return target.provider === "nvidia" || matchesHost(target.baseUrl, NVIDIA_NIM_HOST);
}

function isOpenCode(target                      )          {
	return (
		target.provider === "opencode" || target.provider === "opencode-go" || matchesHost(target.baseUrl, OPENCODE_HOST)
	);
}

/** Provider attribution headers of an identity for a request target. Empty when attribution is off. */
function getAttributionHeaders(target                      , identity                 )                         {
	const attribution = identity?.attribution;
	if (!attribution) return {};
	const { name, url, categories } = attribution;
	if (isOpenRouter(target)) {
		return {
			"HTTP-Referer": url,
			"X-OpenRouter-Title": name,
			...(categories === undefined || categories.length === 0
				? {}
				: { "X-OpenRouter-Categories": categories.join(",") }),
		};
	}
	// NVIDIA expects a capitalized origin, such as `Pi`.
	if (isNvidiaNim(target)) return { "X-BILLING-INVOKE-ORIGIN": name.charAt(0).toUpperCase() + name.slice(1) };
	if (isOpenCode(target)) return { "x-opencode-client": name };
	return {};
}

/**
 * OpenCode's per-conversation routing header for requests to OpenCode, including custom providers pointing at it.
 * Undefined without a session or for other targets.
 */
function getOpenCodeSessionHeaders(
	target                      ,
	sessionId                    ,
)                                     {
	return sessionId && isOpenCode(target) ? { "x-opencode-session": sessionId } : undefined;
}

/**
 * Default headers an identity adds to a request: `User-Agent` and provider attribution. APIs apply them below model,
 * provider, and request headers, so configured headers override them and `null` request headers remove them.
 */
function getIdentityHeaders(target                      , identity                 )                         {
	return { "User-Agent": formatUserAgent(identity), ...getAttributionHeaders(target, identity) };
}

function isMutableModels(models        )                          {
	return "setProvider" in models && "deleteProvider" in models && "clearProviders" in models;
}

/**
 * `models` with `identity` as the default identity of its requests: a request's `identity` overrides it field by
 * field, and it overrides the identity `models` was created with. Everything else is delegated unchanged; a
 * `MutableModels` stays mutable.
 */
                                                                                                   
                                                                                     
function withClientIdentity(models        , identity                )         {
	const withIdentity =                                          (options               )    =>
		({ ...options, identity: mergeClientIdentity(identity, options?.identity) })     ;
	const wrapped         = {
		getProviders: () => models.getProviders(),
		getProvider: (id) => models.getProvider(id),
		getModels: (provider) => models.getModels(provider),
		getModel: (provider, id) => models.getModel(provider, id),
		getModelsOfType: (type, provider) => models.getModelsOfType(type, provider),
		getModelOfType: (type, provider, id) => models.getModelOfType(type, provider, id),
		getAllModels: (provider) => models.getAllModels(provider),
		refresh: (options) => models.refresh(options),
		checkAuth: (providerId, options) => models.checkAuth(providerId, options),
		getAvailable: (providerId, options) => models.getAvailable(providerId, options),
		getAvailableOfType: (type, providerId, options) => models.getAvailableOfType(type, providerId, options),
		getAllAvailable: (providerId, options) => models.getAllAvailable(providerId, options),
		getAuth: models.getAuth.bind(models),
		login: (providerId, type, interaction, options) => models.login(providerId, type, interaction, options),
		logout: (providerId, options) => models.logout(providerId, options),
		stream: (model, context, options) => models.stream(model, context, withIdentity(options)),
		complete: (model, context, options) => models.complete(model, context, withIdentity(options)),
		streamSimple: (model, context, options) => models.streamSimple(model, context, withIdentity(options)),
		completeSimple: (model, context, options) => models.completeSimple(model, context, withIdentity(options)),
		streamDeferred: (model, handle, options) => models.streamDeferred(model, handle, withIdentity(options)),
		fetchDeferred: (model, handle, options) => models.fetchDeferred(model, handle, withIdentity(options)),
		cancelDeferred: (model, handle, options) => models.cancelDeferred(model, handle, withIdentity(options)),
		generateImages: (model, context, options) => models.generateImages(model, context, withIdentity(options)),
		classify: (model, context, options) => models.classify(model, context, withIdentity(options)),
	};
	if (!isMutableModels(models)) return wrapped;
	const mutable                = {
		...wrapped,
		setProvider: (provider) => models.setProvider(provider),
		deleteProvider: (id) => models.deleteProvider(id),
		clearProviders: () => models.clearProviders(),
	};
	return mutable;
}


/***/ }),
/* 7 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   PI_AI_VERSION: () => (/* binding */ PI_AI_VERSION)
/* harmony export */ });
// The @earendil-works/pi-ai package version. scripts/sync-versions.js keeps it in sync with package.json.
const PI_AI_VERSION = "1.5.0";


/***/ }),
/* 8 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   validateToolArguments: () => (/* binding */ validateToolArguments),
/* harmony export */   validateToolCall: () => (/* binding */ validateToolCall)
/* harmony export */ });
/* harmony import */ var _earendil_works_pi_schema__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(9);
/* harmony import */ var _earendil_works_pi_schema_formats__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(17);


                                                  

// One compiled validator per schema object: tool schemas are long-lived, so the WeakMap drops entries with their tools.
const validatorCache = new WeakMap                           ();

function getValidator(schema                    )                    {
	const cached = validatorCache.get(schema);
	if (cached) {
		return cached;
	}
	// A $ref that names nothing (a dangling definition in an MCP server's schema) rejects only values at its place,
	// instead of making every call of the tool fail.
	const validator = (0,_earendil_works_pi_schema__WEBPACK_IMPORTED_MODULE_0__.compile)(schema, { formats: _earendil_works_pi_schema_formats__WEBPACK_IMPORTED_MODULE_1__.formats, coerce: true, unresolvedRefs: "reject" });
	validatorCache.set(schema, validator);
	return validator;
}

/** A pi-schema error path (a JSON Pointer) as the dotted path the model sees: `/edits/0/oldText` -> `edits.0.oldText`. */
function formatValidationPath(pointer        )         {
	if (pointer === "") return "root";
	return pointer
		.slice(1)
		.split("/")
		.map((segment) => segment.replace(/~1/g, "/").replace(/~0/g, "~"))
		.join(".");
}

/**
 * Finds a tool by name and validates the tool call arguments against its JSON Schema
 * @param tools Array of tool definitions
 * @param toolCall The tool call from the LLM
 * @returns The validated arguments
 * @throws Error if tool is not found or validation fails
 */
function validateToolCall(tools        , toolCall          )      {
	const tool = tools.find((t) => t.name === toolCall.name);
	if (!tool) {
		throw new Error(`Tool "${toolCall.name}" not found`);
	}
	return validateToolArguments(tool, toolCall);
}

/**
 * Validates tool call arguments against the tool's JSON Schema (any JSON Schema: pi-schema's builders, TypeBox, plain
 * objects). The arguments are coerced first (pi-schema's `parse()`): models often send `"42"` for a number, a single
 * value for an array, or `null` for an omitted optional property.
 * @param tool The tool definition with its JSON Schema
 * @param toolCall The tool call from the LLM
 * @returns The validated (and potentially coerced) arguments
 * @throws Error with formatted message if validation fails
 */
function validateToolArguments(tool      , toolCall          )      {
	if (toolCall.argumentsError) {
		throw new Error(`Invalid arguments for tool "${toolCall.name}": ${toolCall.argumentsError}`);
	}
	// A copy, so tools that modify their arguments never modify the tool call in the transcript.
	const result = getValidator(tool.parameters).parse(structuredClone(toolCall.arguments));
	if (result.ok) {
		return result.value;
	}

	const errors = result.errors.map((error) => `  - ${formatValidationPath(error.path)}: ${error.message}`).join("\n");

	const errorMessage = `Validation failed for tool "${toolCall.name}":\n${errors}\n\nReceived arguments:\n${JSON.stringify(toolCall.arguments, null, 2)}`;

	throw new Error(errorMessage);
}


/***/ }),
/* 9 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   CoercingValidator: () => (/* binding */ CoercingValidator),
/* harmony export */   Validator: () => (/* binding */ Validator),
/* harmony export */   compile: () => (/* binding */ compile)
/* harmony export */ });
/* harmony import */ var _coerce_ts__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(10);
/* harmony import */ var _compiler_ts__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(13);
/* harmony import */ var _explain_ts__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(16);
// compile(): a schema to a Validator, whose `check` is the fast boolean path and whose `errors` explains failures.

                                            



                                                                                                  

/**
 * Compiles `schema` into a Validator. Throws if the schema is malformed or a `$ref` names nothing (register other
 * documents with `options.schemas`). The schema is read, never modified. With `coerce: true` the validator also
 * coerces and parses loosely typed input.
 */
                                              
	          
	                                                    
                                
                                                                                                         
function compile                      (
	schema   ,
	options                 = NO_OPTIONS,
)                                                      {
	const check = compileCheck(schema, options);
	return options.coerce === true
		? new CoercingValidator(schema, options, check)
		: new Validator(schema, options, check);
}

const NO_OPTIONS                 = Object.freeze({});

/** A compiled schema whose valid values have type `T` (`unknown` unless the schema was built with the builders). */
class Validator              {
	/** Whether `value` is valid. A plain function: it may be passed around without its validator. */
	         check                                ;
	                   schema            ;
	                   options                ;
	        explainer                        = undefined;

	constructor(schema            , options                , check       ) {
		// A compiled check is exactly the test that a value has the schema's static type.
		this.check = check                                  ;
		this.schema = schema;
		this.options = options;
	}

	/**
	 * Why `value` is invalid: one error per failing keyword, `[]` when it is valid. Built on the first failure and
	 * kept, so a validator whose values always pass never pays for it.
	 */
	errors(value         )                    {
		if (this.check(value)) return [];
		this.explainer ??= new _explain_ts__WEBPACK_IMPORTED_MODULE_2__.Explainer(compiler(this.schema, this.options), this.schema);
		return this.explainer.errors(value);
	}
}

/** A validator compiled with `coerce: true`: it also coerces loosely typed input towards the schema. */
class CoercingValidator              extends Validator    {
	        coercer                      = undefined;

	/**
	 * `value` coerced towards the schema: numeric strings to numbers, `"true"` to `true`, a single value to a one-item
	 * array, an optional `null` dropped, the first union branch the value converts to (the README lists every rule).
	 * Only the changed paths are copied; `value` comes back itself when nothing changes, and is never modified. Values
	 * that cannot be coerced stay as they are. The rules are built on the first call.
	 */
	coerce(value         )          {
		this.coercer ??= new _coerce_ts__WEBPACK_IMPORTED_MODULE_0__.Coercer(compiler(this.schema, this.options), this.schema);
		return this.coercer.coerce(value);
	}

	/** Coerces `value`, then checks it: the coerced value when it is valid, else its errors. */
	parse(value         )                 {
		const coerced = this.coerce(value);
		return this.check(coerced) ? { ok: true, value: coerced } : { ok: false, errors: this.errors(coerced) };
	}
}

function compileCheck(schema            , options                )        {
	return compiler(schema, options).root();
}

// Most schemas compile without tracking the dynamic scope; one with a bookended $dynamicRef compiles again with it.
function compiler(schema            , options                )           {
	const plain = new _compiler_ts__WEBPACK_IMPORTED_MODULE_1__.Compiler(schema, options, false);
	try {
		plain.root();
		return plain;
	} catch (error) {
		if (error !== _compiler_ts__WEBPACK_IMPORTED_MODULE_1__.RESTART) throw error;
		return new _compiler_ts__WEBPACK_IMPORTED_MODULE_1__.Compiler(schema, options, true);
	}
}


/***/ }),
/* 10 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   Coercer: () => (/* binding */ Coercer)
/* harmony export */ });
/* harmony import */ var _resolver_ts__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(11);
/* harmony import */ var _shared_ts__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(12);
// Coercion: turns a loosely typed value (a model's tool call arguments, form input, query strings) into the value a
// schema most likely meant, before it is checked. Only validators compiled with `{ coerce: true }` have it, and they
// build it on their first coerce() or parse(); the check path never runs it.
//
// Three passes, in this order:
// 1. Optional nulls: a `null` in an optional property whose schema rejects null is dropped, as if omitted.
// 2. Conversion rules ported from TypeBox 1.3.27's Value.Convert (MIT), keyed on JSON Schema structure (`$ref`, `const`,
//    `enum`, `anyOf`/`oneOf`, `allOf`, `type`, `items`/`prefixItems`, `properties`/`patternProperties`) instead of
//    TypeBox's kind marks: `null` becomes "null" for a string, `"3.5"` becomes 3 for an integer, a single value becomes
//    a one-item array, and a union takes the first branch whose conversion passes.
// 3. Primitive coercion by `type` (also type arrays), as in Ajv's `coerceTypes`: what pass 2 left, for example a type
//    array `["integer", "null"]`, or `""` for `null`.
// A value that cannot be coerced is left as it is; the check then reports it.
//
// Every pass is copy-on-write: an object or array is copied only when one of its members changes, so an unchanged
// value comes back as the same reference and the input is never modified. All passes walk the same keywords for
// members: `properties`, then `patternProperties`, then `additionalProperties` for keys neither names; tuple items by
// `prefixItems` (or a draft-7 `items` array), the rest by `items`. Draft 7's `additionalItems` is not coerced.

                                              


                                               

/** Coerces values for one compiled schema. */
class Coercer {
	                 c          ;
	                 schema         ;
	/**
	 * The (schema node, value) pairs pass 2 is converting through a re-entry point, as a stack. Conversion can enter
	 * a node again with the same value only through a `$ref` or by wrapping a single value into an array (also for
	 * schemas that cycle through shared JS objects): a recursive schema that meets a value no branch accepts would
	 * convert it without end. A pair already on the stack (primitives by value, objects by reference) leaves the value
	 * unchanged. Only those two points push, so schemas without references or wraps pay nothing.
	 */
	                 entered            = [];
	/** How often a re-entry was cut short in this coerce() call; results computed across one are not memoized. */
	        cuts = 0;
	/**
	 * Results of `$ref` targets by (node, value) within one coerce() call: a recursive union whose branches all
	 * convert the same children (and all fail) would convert them once per branch, exponentially in the depth.
	 */
	        memo                                                 = undefined;

	constructor(compiler          , schema         ) {
		this.c = compiler;
		this.schema = schema;
	}

	/** `value` coerced: a copy along the changed paths only, or `value` itself when nothing changes. */
	coerce(value         )          {
		const base = this.c.r.rootBase;
		const withoutNulls = this.dropOptionalNulls(value, this.schema, base);
		return this.coerceTypes(this.convertOnly(withoutNulls), this.schema, base);
	}

	/** Pass 2 alone, as TypeBox's `Value.Convert` (the TypeBox conformance tests run it). */
	convertOnly(value         )          {
		try {
			return this.convert(this.schema, this.c.r.rootBase, value);
		} finally {
			this.memo = undefined;
			this.cuts = 0;
		}
	}

	/** `node` converted for `value` at a re-entry point: unchanged when that pair is already being converted. */
	        reenter(node        , base        , value         , memoize         )          {
		const entered = this.entered;
		for (let i = 0; i < entered.length; i += 2) {
			if (entered[i] === node && Object.is(entered[i + 1], value)) {
				this.cuts++;
				return value;
			}
		}
		const memo = memoize ? this.memo?.get(node) : undefined;
		if (memo?.has(value)) return memo.get(value);
		const cuts = this.cuts;
		entered.push(node, value);
		let result         ;
		try {
			result = this.convert(node, base, value);
		} finally {
			entered.length -= 2;
		}
		if (memoize && this.cuts === cuts) {
			this.memo ??= new Map();
			let values = this.memo.get(node);
			if (values === undefined) {
				values = new Map();
				this.memo.set(node, values);
			}
			values.set(value, result);
		}
		return result;
	}

	/** Whether `value` passes the schema `node`; undefined when `node` cannot be compiled. */
	        passes(node         , base        , value         )                      {
		try {
			return this.c.check(node, this.baseOf(node, base))(value);
		} catch {
			return undefined;
		}
	}

	        baseOf(node         , base        )         {
		return (0,_resolver_ts__WEBPACK_IMPORTED_MODULE_0__.isSchemaObject)(node) ? (this.c.r.baseOf.get(node) ?? base) : base;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Pass 1: optional nulls
	// ---------------------------------------------------------------------------------------------------------------

	        dropOptionalNulls(value         , schema         , base        )          {
		if (!(0,_resolver_ts__WEBPACK_IMPORTED_MODULE_0__.isSchemaObject)(schema)) return value;
		const b = this.baseOf(schema, base);
		if (Array.isArray(value)) {
			return mapItems(value, (item, i) => {
				const itemSchema = itemSchemaAt(schema, i);
				return itemSchema === undefined ? item : this.dropOptionalNulls(item, itemSchema, b);
			});
		}
		if (!isPlainObject(value)) return value;
		const properties = (0,_resolver_ts__WEBPACK_IMPORTED_MODULE_0__.isSchemaObject)(schema.properties) ? schema.properties : {};
		const patterns = (0,_resolver_ts__WEBPACK_IMPORTED_MODULE_0__.isSchemaObject)(schema.patternProperties)
			? Object.entries(schema.patternProperties).map(([pattern, item]) => [(0,_shared_ts__WEBPACK_IMPORTED_MODULE_1__.sharedRegex)(pattern), item]         )
			: [];
		const additional = (0,_resolver_ts__WEBPACK_IMPORTED_MODULE_0__.isSchemaObject)(schema.additionalProperties) ? schema.additionalProperties : undefined;
		if (patterns.length === 0 && additional === undefined && !(0,_resolver_ts__WEBPACK_IMPORTED_MODULE_0__.isSchemaObject)(schema.properties)) return value;
		const required = new Set(Array.isArray(schema.required) ? schema.required : []);
		let out                                     ;
		const set = (key        , next         ) => {
			if (next === (out ?? value)[key]) return;
			out ??= { ...value };
			out[key] = next;
		};
		for (const [key, property] of Object.entries(properties)) {
			if (!Object.hasOwn(value, key)) continue;
			const isRef = (0,_resolver_ts__WEBPACK_IMPORTED_MODULE_0__.isSchemaObject)(property) && typeof property.$ref === "string";
			if (value[key] === null && !required.has(key) && !isRef && this.passes(property, b, null) === false) {
				out ??= { ...value };
				delete out[key];
				continue;
			}
			set(key, this.dropOptionalNulls(value[key], property, b));
		}
		// Values of pattern and additional properties are walked for their own optional nulls; the keys themselves
		// are not optional properties, so they stay.
		if (patterns.length > 0 || additional !== undefined) {
			for (const key of Object.keys(value)) {
				let matched = Object.hasOwn(properties, key);
				for (const [pattern, item] of patterns) {
					if (!pattern.test(key)) continue;
					matched = true;
					set(key, this.dropOptionalNulls((out ?? value)[key], item, b));
				}
				if (!matched && additional !== undefined) set(key, this.dropOptionalNulls(value[key], additional, b));
			}
		}
		return out ?? value;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Pass 2: TypeBox's conversion rules
	// ---------------------------------------------------------------------------------------------------------------

	        convert(node         , base        , value         )          {
		if (!(0,_resolver_ts__WEBPACK_IMPORTED_MODULE_0__.isSchemaObject)(node)) return value;
		const b = this.baseOf(node, base);
		if (typeof node.$ref === "string") {
			let target                                 ;
			try {
				target = this.c.r.resolve(node.$ref, b);
			} catch {
				return value;
			}
			return (0,_resolver_ts__WEBPACK_IMPORTED_MODULE_0__.isSchemaObject)(target.node) ? this.reenter(target.node, target.base, value, true) : value;
		}
		return this.convertNode(node, b, value);
	}

	/** One schema node's conversion: its first keyword among `const`, `enum`, the unions, `allOf` and `type`. */
	        convertNode(node              , b        , value         )          {
		if (Object.hasOwn(node, "const")) return convertLiteral(node.const, value);
		if (Array.isArray(node.enum)) return this.convertEnum(node, node.enum, b, value);
		// A union or intersection node may also declare members (plain JSON Schema; TypeBox's never do): they convert
		// after the branches.
		if (Array.isArray(node.anyOf)) return this.convertUnion(node, node.anyOf, b, value);
		if (Array.isArray(node.oneOf)) return this.convertUnion(node, node.oneOf, b, value);
		if (Array.isArray(node.allOf)) {
			let next = value;
			for (const member of node.allOf) next = this.convert(member, b, next);
			return this.convertMembers(node, b, next);
		}
		switch (node.type) {
			case "string":
				return orValue(tryString(value), value);
			case "number":
				return orValue(tryNumber(value), value);
			case "integer": {
				const result = tryNumber(value);
				return result === undefined ? value : Math.trunc(result.value);
			}
			case "boolean":
				return orValue(tryBoolean(value), value);
			case "null":
				return orValue(tryNull(value), value);
			case "array":
				return this.convertArray(node, b, value);
			case "object":
				return this.convertObject(node, b, value);
			case undefined:
				if (node.properties !== undefined || node.patternProperties !== undefined) {
					return this.convertObject(node, b, value);
				}
				if (node.items !== undefined || node.prefixItems !== undefined) return this.convertArray(node, b, value);
				return value;
			default:
				return value;
		}
	}

	/** An enum is a union of its values: a member stays, else the first value the input converts to. */
	        convertEnum(node              , values                    , base        , value         )          {
		if (values.some((member) => member === value)) return value;
		for (const member of values) {
			const candidate = convertLiteral(member, value);
			if (candidate !== value && this.passes(node, base, candidate) === true) return candidate;
		}
		return value;
	}

	/**
	 * A value one branch accepts stays; else the first branch's conversion that the whole union accepts. Branches are
	 * converted in order and only until one is accepted, so a later recursive branch is not entered needlessly. A node
	 * that also declares members (plain JSON Schema; TypeBox's never do) converts them into each candidate before it is
	 * checked, so a branch is not discarded for a sibling property that is converted after it.
	 */
	        convertUnion(node              , branches                    , base        , value         )          {
		if (branches.some((branch) => this.passes(branch, base, value) === true))
			return this.convertMembers(node, base, value);
		for (const branch of branches) {
			const candidate = this.convertMembers(node, base, this.convert(branch, base, value));
			if (this.passes(node, base, candidate) === true) return candidate;
		}
		return this.convertMembers(node, base, value);
	}

	/** The object members of a union or intersection node, when it declares any. */
	        convertMembers(node              , base        , value         )          {
		const declares =
			node.properties !== undefined ||
			node.patternProperties !== undefined ||
			node.additionalProperties !== undefined;
		return declares ? this.convertObject(node, base, value) : value;
	}

	        convertArray(node              , base        , value         )          {
		const prefix = Array.isArray(node.items)
			? node.items
			: Array.isArray(node.prefixItems)
				? node.prefixItems
				: undefined;
		if (prefix !== undefined) {
			// A tuple converts its items and never wraps a single value.
			if (!Array.isArray(value)) return value;
			const rest = Array.isArray(node.items) ? undefined : node.items;
			return mapItems(value, (item, i) => {
				const schema = i < prefix.length ? prefix[i] : rest;
				return schema === undefined ? item : this.convert(schema, base, item);
			});
		}
		if (Array.isArray(value)) {
			return node.items === undefined ? value : mapItems(value, (item) => this.convert(node.items, base, item));
		}
		// The wrapped single value is converted by the item schema with the same value: a re-entry point.
		const item = node.items;
		return [(0,_resolver_ts__WEBPACK_IMPORTED_MODULE_0__.isSchemaObject)(item) ? this.reenter(item, base, value, false) : value];
	}

	        convertObject(node              , base        , value         )          {
		if (!isPlainObject(value)) return value;
		const properties = (0,_resolver_ts__WEBPACK_IMPORTED_MODULE_0__.isSchemaObject)(node.properties) ? node.properties : {};
		const patterns = (0,_resolver_ts__WEBPACK_IMPORTED_MODULE_0__.isSchemaObject)(node.patternProperties)
			? Object.entries(node.patternProperties).map(([pattern, schema]) => [(0,_shared_ts__WEBPACK_IMPORTED_MODULE_1__.sharedRegex)(pattern), schema]         )
			: [];
		const additional = (0,_resolver_ts__WEBPACK_IMPORTED_MODULE_0__.isSchemaObject)(node.additionalProperties) ? node.additionalProperties : undefined;
		let out                                     ;
		for (const key of Object.keys(value)) {
			// An undefined property counts as absent.
			if (value[key] === undefined) continue;
			let next          = value[key];
			let matched = Object.hasOwn(properties, key);
			if (matched) next = this.convert(properties[key], base, next);
			for (const [pattern, schema] of patterns) {
				if (!pattern.test(key)) continue;
				matched = true;
				next = this.convert(schema, base, next);
			}
			if (!matched && additional !== undefined) next = this.convert(additional, base, next);
			if (next !== value[key]) {
				out ??= { ...value };
				out[key] = next;
			}
		}
		return out ?? value;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Pass 3: primitive coercion by type
	// ---------------------------------------------------------------------------------------------------------------

	        coerceTypes(value         , schema         , base        )          {
		if (!(0,_resolver_ts__WEBPACK_IMPORTED_MODULE_0__.isSchemaObject)(schema)) return value;
		const b = this.baseOf(schema, base);
		let next = value;
		if (Array.isArray(schema.allOf)) for (const member of schema.allOf) next = this.coerceTypes(next, member, b);
		if (Array.isArray(schema.anyOf)) next = this.coerceUnion(next, schema.anyOf, b);
		if (Array.isArray(schema.oneOf)) next = this.coerceUnion(next, schema.oneOf, b);
		const types = typesOf(schema);
		const matchesUnionMember = types.length > 1 && types.some((type) => matchesType(next, type));
		if (types.length > 0 && !matchesUnionMember) {
			for (const type of types) {
				const candidate = coercePrimitive(next, type);
				if (candidate !== next) {
					next = candidate;
					break;
				}
			}
		}
		if (types.includes("object") && isPlainObject(next)) next = this.coerceProperties(next, schema, b);
		if (types.includes("array") && Array.isArray(next)) next = this.coerceItems(next, schema, b);
		return next;
	}

	        coerceUnion(value         , branches                    , base        )          {
		for (const branch of branches) if (this.passes(branch, base, value) === true) return value;
		for (const branch of branches) {
			const coerced = this.coerceTypes(value, branch, base);
			if (this.passes(branch, base, coerced) === true) return coerced;
		}
		return value;
	}

	        coerceProperties(
		value                         ,
		schema              ,
		base        ,
	)                          {
		const properties = (0,_resolver_ts__WEBPACK_IMPORTED_MODULE_0__.isSchemaObject)(schema.properties) ? schema.properties : {};
		const patterns = (0,_resolver_ts__WEBPACK_IMPORTED_MODULE_0__.isSchemaObject)(schema.patternProperties)
			? Object.entries(schema.patternProperties).map(([pattern, item]) => [(0,_shared_ts__WEBPACK_IMPORTED_MODULE_1__.sharedRegex)(pattern), item]         )
			: [];
		const additional = (0,_resolver_ts__WEBPACK_IMPORTED_MODULE_0__.isSchemaObject)(schema.additionalProperties) ? schema.additionalProperties : undefined;
		let out                                     ;
		const set = (key        , next         ) => {
			if (next === (out ?? value)[key]) return;
			out ??= { ...value };
			out[key] = next;
		};
		for (const [key, property] of Object.entries(properties)) {
			if (key in value) set(key, this.coerceTypes(value[key], property, base));
		}
		for (const key of Object.keys(value)) {
			let matched = Object.hasOwn(properties, key);
			for (const [pattern, item] of patterns) {
				if (!pattern.test(key)) continue;
				matched = true;
				set(key, this.coerceTypes((out ?? value)[key], item, base));
			}
			if (!matched && additional !== undefined) set(key, this.coerceTypes(value[key], additional, base));
		}
		return out ?? value;
	}

	        coerceItems(value           , schema              , base        )            {
		return mapItems(value, (item, i) => {
			const itemSchema = itemSchemaAt(schema, i);
			return (0,_resolver_ts__WEBPACK_IMPORTED_MODULE_0__.isSchemaObject)(itemSchema) ? this.coerceTypes(item, itemSchema, base) : item;
		});
	}
}

/**
 * The schema of an array's item at `index`: a tuple's (`prefixItems`, or a draft-7 `items` array) for its prefix,
 * `items` after `prefixItems`, or `items` for every item. Undefined when nothing applies.
 */
function itemSchemaAt(schema              , index        )          {
	if (Array.isArray(schema.items)) return schema.items[index];
	if (Array.isArray(schema.prefixItems) && index < schema.prefixItems.length) return schema.prefixItems[index];
	return schema.items;
}

/** `items` mapped by `map`; the same array when every item maps to itself. */
function mapItems(items           , map                                           )            {
	let out                       ;
	for (let i = 0; i < items.length; i++) {
		const next = map(items[i], i);
		if (next === items[i]) continue;
		out ??= items.slice();
		out[i] = next;
	}
	return out ?? items;
}

// -------------------------------------------------------------------------------------------------------------------
// TypeBox's conversions of one value (value/convert/try in TypeBox 1.3.27): a result, or undefined when there is none
// -------------------------------------------------------------------------------------------------------------------

                                                      

function orValue(result                    , value         )          {
	return result === undefined ? value : result.value;
}

/** A literal (`const`, an enum value) taken from a value of another type that converts to exactly it. */
function convertLiteral(literal         , value         )          {
	if (literal === value) return value;
	const result =
		typeof literal === "string"
			? tryString(value)
			: typeof literal === "number"
				? tryNumber(value)
				: typeof literal === "boolean"
					? tryBoolean(value)
					: undefined;
	return result !== undefined && result.value === literal ? result.value : value;
}

function tryString(value         )                    {
	if (typeof value === "bigint" || typeof value === "boolean" || isFiniteNumber(value))
		return { value: String(value) };
	if (value === null) return { value: "null" };
	if (typeof value === "string") return { value };
	if (value === undefined) return { value: "" };
	return undefined;
}

const MAX_BIGINT = BigInt(Number.MAX_SAFE_INTEGER);
const MIN_BIGINT = BigInt(Number.MIN_SAFE_INTEGER);

function tryNumber(value         )                    {
	if (typeof value === "bigint")
		return value <= MAX_BIGINT && value >= MIN_BIGINT ? { value: Number(value) } : undefined;
	if (typeof value === "boolean") return { value: value ? 1 : 0 };
	if (isFiniteNumber(value)) return { value };
	if (value === null || value === undefined) return { value: 0 };
	if (typeof value !== "string") return undefined;
	const coerced = +value;
	if (Number.isFinite(coerced)) return { value: coerced };
	const lowercase = value.toLowerCase();
	if (lowercase === "false") return { value: 0 };
	if (lowercase === "true") return { value: 1 };
	const big = bigIntOfString(value);
	return big !== undefined && big <= MAX_BIGINT && big >= MIN_BIGINT ? { value: Number(big) } : undefined;
}

/** TypeBox's TryBigInt of a string: `12n`, `12.5` (truncated) or `12`; true and false are not reached from tryNumber. */
function bigIntOfString(value        )                     {
	if (/^-?(0|[1-9]\d*)n$/.test(value)) return BigInt(value.slice(0, -1));
	if (/^-?(0|[1-9]\d*)\.\d+$/.test(value)) return BigInt(value.split(".")[0]);
	if (/^-?(0|[1-9]\d*)$/.test(value)) return BigInt(value);
	return undefined;
}

function tryBoolean(value         )                     {
	if (typeof value === "bigint") return value === 0n ? { value: false } : value === 1n ? { value: true } : undefined;
	if (typeof value === "boolean") return { value };
	if (isFiniteNumber(value)) return value === 0 ? { value: false } : value === 1 ? { value: true } : undefined;
	if (value === null || value === undefined) return { value: false };
	if (typeof value !== "string") return undefined;
	const lowercase = value.toLowerCase();
	if (lowercase === "false" || value === "0") return { value: false };
	if (lowercase === "true" || value === "1") return { value: true };
	return undefined;
}

function tryNull(value         )                  {
	if (typeof value === "bigint") return value === 0n ? { value: null } : undefined;
	if (typeof value === "boolean") return value === false ? { value: null } : undefined;
	if (isFiniteNumber(value)) return value === 0 ? { value: null } : undefined;
	if (value === null || value === undefined) return { value: null };
	if (typeof value !== "string") return undefined;
	const lowercase = value.toLowerCase();
	return lowercase === "undefined" || lowercase === "null" || value === "" || value === "0"
		? { value: null }
		: undefined;
}

function isFiniteNumber(value         )                  {
	return typeof value === "number" && Number.isFinite(value);
}

// -------------------------------------------------------------------------------------------------------------------
// Primitive coercion by type (pass 3)
// -------------------------------------------------------------------------------------------------------------------

function typesOf(schema              )           {
	if (typeof schema.type === "string") return [schema.type];
	if (Array.isArray(schema.type)) return schema.type.filter((type)                 => typeof type === "string");
	return [];
}

function matchesType(value         , type        )          {
	switch (type) {
		case "number":
			return typeof value === "number";
		case "integer":
			return typeof value === "number" && Number.isInteger(value);
		case "boolean":
			return typeof value === "boolean";
		case "string":
			return typeof value === "string";
		case "null":
			return value === null;
		case "array":
			return Array.isArray(value);
		case "object":
			return isPlainObject(value);
		default:
			return false;
	}
}

function coercePrimitive(value         , type        )          {
	switch (type) {
		case "number":
		case "integer": {
			if (value === null) return 0;
			if (typeof value === "string" && value.trim() !== "") {
				const parsed = Number(value);
				if (type === "number" ? Number.isFinite(parsed) : Number.isInteger(parsed)) return parsed;
			}
			if (typeof value === "boolean") return value ? 1 : 0;
			return value;
		}
		case "boolean":
			if (value === null) return false;
			if (value === "true" || value === 1) return true;
			if (value === "false" || value === 0) return false;
			return value;
		case "string":
			if (value === null) return "";
			if (typeof value === "number" || typeof value === "boolean") return String(value);
			return value;
		case "null":
			return value === "" || value === 0 || value === false ? null : value;
		default:
			return value;
	}
}

function isPlainObject(value         )                                   {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}


/***/ }),
/* 11 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   DEFAULT_BASE: () => (/* binding */ DEFAULT_BASE),
/* harmony export */   Resolver: () => (/* binding */ Resolver),
/* harmony export */   documentOf: () => (/* binding */ documentOf),
/* harmony export */   isSchemaObject: () => (/* binding */ isSchemaObject),
/* harmony export */   resolveUri: () => (/* binding */ resolveUri)
/* harmony export */ });
// Reference resolution of one compile: schema resources, anchors and JSON Pointers over the root schema and the
// registered documents. A Resolver lives only while a schema compiles (or while its errors are explained).

                                                                           

/** A schema and the base URI its references resolve against. */
                           
	                       
	                      
 

/** The base URI of a root schema without `$id`; relative references resolve against it. */
const DEFAULT_BASE = "https://json-schema.invalid/root.json";

/**
 * Resolves references in the root schema and the registered documents. The schema is indexed on the first
 * reference, so a schema without references is never walked for identifiers.
 */
class Resolver {
	         draft                 ;
	         rootBase        ;
	/** Schema object to the base URI it was indexed under. */
	         baseOf = new Map                ();
	/** URI (with a `#name` fragment) of every `$dynamicAnchor`, to its schema. */
	         dynamicAnchors = new Map                      ();
	                 root            ;
	                 options                ;
	                 registry = new Map                    ();
	                 rejectUnresolved         ;
	/** The references that named nothing and resolved to `false` (with `unresolvedRefs: "reject"`). */
	                 unresolved = new Set        ();
	                 resources = new Map                 ();
	                 anchors = new Map                      ();
	        indexed = false;

	constructor(root            , options                ) {
		this.draft = options.draft === "7" ? "7" : "2020-12";
		this.root = root;
		this.options = options;
		this.rejectUnresolved = options.unresolvedRefs === "reject";
		this.rootBase = isSchemaObject(root) ? this.baseFor(root, DEFAULT_BASE) : DEFAULT_BASE;
	}

	/**
	 * The base URI of `node`, whose parent has `base`: its `$id` changes it, unless draft 7 makes the `$id` inert. Once
	 * the schema is indexed, the indexed base (a resolved reference target already has its own).
	 */
	baseFor(node              , base        )         {
		const indexed = this.baseOf.get(node);
		if (indexed !== undefined) return indexed;
		const id = node.$id;
		if (typeof id !== "string") return base;
		if (this.draft === "7" && (typeof node.$ref === "string" || id.startsWith("#"))) return base;
		return documentOf(resolveUri(id, base));
	}

	/** Indexes the root schema and the registered documents' URIs (once). */
	ensureIndexed()       {
		if (this.indexed) return;
		this.indexed = true;
		for (const [uri, schema] of Object.entries(this.options.schemas ?? {})) {
			this.registry.set(documentOf(resolveUri(uri, DEFAULT_BASE)), schema);
		}
		this.resources.set(DEFAULT_BASE, this.root);
		this.index(this.root, DEFAULT_BASE);
	}

	/**
	 * The schema a reference names, with the base URI it compiles under. Throws if it names nothing, or, with
	 * `unresolvedRefs: "reject"`, resolves to `false`, the schema no value passes.
	 */
	resolve(ref        , base        )           {
		this.ensureIndexed();
		if (!this.rejectUnresolved) return this.find(ref, base);
		try {
			return this.find(ref, base);
		} catch {
			this.unresolved.add(resolveUri(ref, base));
			return { node: false, base };
		}
	}

	/** Whether `ref`, from `base`, named nothing and resolved to `false`. */
	isUnresolved(ref        , base        )          {
		return this.unresolved.has(resolveUri(ref, base));
	}

	        find(ref        , base        )           {
		const uri = resolveUri(ref, base);
		const doc = documentOf(uri);
		const fragment = uri.length > doc.length ? decodeURIComponent(uri.slice(doc.length + 1)) : "";
		const root = this.resources.has(doc) ? this.resources.get(doc) : this.load(doc);
		if (root === undefined) throw new Error(`Unresolved $ref ${ref} (no schema registered for ${doc})`);
		if (fragment === "") return { node: root, base: doc };
		if (fragment.startsWith("/")) return this.pointer(root, doc, fragment);
		const target = this.anchors.get(`${doc}#${fragment}`);
		if (target === undefined) throw new Error(`Unresolved $ref ${ref} (no anchor ${fragment} in ${doc})`);
		return { node: target, base: this.baseOf.get(target) ?? doc };
	}

	/** Whether `node` is the root of the schema resource at `base`. */
	isResource(node        , base        )          {
		this.ensureIndexed();
		return this.resources.get(base) === node;
	}

	// Walks only keywords that hold subschemas: an `$id` inside `enum`, `const` or an unknown keyword names nothing.
	        index(node         , base        )       {
		if (!isSchemaObject(node) || this.baseOf.has(node)) return;
		let b = base;
		// In draft 7 a `$ref` makes every sibling keyword, `$id` included, inert.
		const refOnly = this.draft === "7" && typeof node.$ref === "string";
		if (!refOnly && typeof node.$id === "string") {
			if (this.draft === "7" && node.$id.startsWith("#")) this.anchors.set(documentOf(b) + node.$id, node);
			else {
				const uri = resolveUri(node.$id, b);
				b = documentOf(uri);
				this.resources.set(b, node);
				if (uri.length > b.length + 1) this.anchors.set(uri, node);
			}
		}
		this.baseOf.set(node, b);
		if (refOnly) return;
		if (this.draft !== "7") {
			if (typeof node.$anchor === "string") this.anchors.set(`${b}#${node.$anchor}`, node);
			if (typeof node.$dynamicAnchor === "string") {
				this.anchors.set(`${b}#${node.$dynamicAnchor}`, node);
				this.dynamicAnchors.set(`${b}#${node.$dynamicAnchor}`, node);
			}
		}
		for (const key of SCHEMA_KEYWORDS) this.index(node[key], b);
		for (const key of MAP_KEYWORDS) {
			const map = node[key];
			if (isSchemaObject(map)) for (const k of Object.keys(map)) this.index(map[k], b);
		}
		for (const key of ARRAY_KEYWORDS) {
			const list = node[key];
			if (Array.isArray(list)) for (const s of list) this.index(s, b);
		}
	}

	        load(doc        )                         {
		const schema = this.registry.get(doc);
		if (schema === undefined) return undefined;
		this.resources.set(doc, schema);
		this.index(schema, doc);
		return schema;
	}

	        pointer(root         , base        , fragment        )           {
		let node = root;
		let b = base;
		for (const raw of fragment.split("/").slice(1)) {
			const key = raw.replace(/~1/g, "/").replace(/~0/g, "~");
			node =
				typeof node === "object" && node !== null && Object.hasOwn(node, key)
					? (node                )[key]
					: undefined;
			if (node === undefined) throw new Error(`Unresolved $ref #${fragment} in ${base}`);
			if (typeof node === "object" && node !== null) b = this.baseOf.get(node) ?? b;
		}
		return { node, base: b };
	}
}

const SCHEMA_KEYWORDS = [
	"additionalProperties",
	"propertyNames",
	"contains",
	"not",
	"if",
	"then",
	"else",
	"unevaluatedProperties",
	"unevaluatedItems",
	"additionalItems",
	"contentSchema",
	"items",
];

const MAP_KEYWORDS = ["properties", "patternProperties", "$defs", "definitions", "dependentSchemas", "dependencies"];

const ARRAY_KEYWORDS = ["allOf", "anyOf", "oneOf", "prefixItems", "items"];

/** Whether `value` is a schema object (not `true`/`false`, an array or a primitive). */
function isSchemaObject(value         )                        {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** `ref` resolved against `base`. URNs and other bases a URL parser rejects take fragment-only references. */
function resolveUri(ref        , base        )         {
	try {
		return new URL(ref, base).href;
	} catch {
		return ref.startsWith("#") ? documentOf(base) + ref : ref;
	}
}

/** `uri` without its fragment. */
function documentOf(uri        )         {
	const hash = uri.indexOf("#");
	return hash < 0 ? uri : uri.slice(0, hash);
}


/***/ }),
/* 12 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   sharedRegex: () => (/* binding */ sharedRegex),
/* harmony export */   sharedRegexCount: () => (/* binding */ sharedRegexCount)
/* harmony export */ });
// Regular expressions shared across all compiled schemas while one still uses them: tool schemas repeat a few
// patterns many times, and a RegExp is large once it has run. The cache holds its values weakly and drops an entry
// once its RegExp is collected, so it keeps nothing alive.

/**
 * The regular expression of a `pattern` source, shared by source. Patterns are ECMAScript regular expressions with
 * the `u` flag; a source that is invalid with it (some older schemas escape characters `u` rejects) compiles
 * without it.
 */
function sharedRegex(source        )         {
	const hit = regexes.get(source)?.deref();
	if (hit !== undefined) return hit;
	let re        ;
	try {
		re = new RegExp(source, "u");
	} catch {
		re = new RegExp(source);
	}
	regexes.set(source, new WeakRef(re));
	regexCleanup.register(re, source);
	return re;
}

const regexes = new Map                         ();

const regexCleanup = new FinalizationRegistry        ((source) => {
	if (regexes.get(source)?.deref() === undefined) regexes.delete(source);
});

/** The number of cached regular expressions (for tests). */
function sharedRegexCount()         {
	return regexes.size;
}


/***/ }),
/* 13 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   Compiler: () => (/* binding */ Compiler),
/* harmony export */   NEUTRAL_KEYWORDS: () => (/* binding */ NEUTRAL_KEYWORDS),
/* harmony export */   RESTART: () => (/* binding */ RESTART)
/* harmony export */ });
/* harmony import */ var _annotations_ts__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(14);
/* harmony import */ var _checks_ts__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(15);
/* harmony import */ var _resolver_ts__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(11);
/* harmony import */ var _shared_ts__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(12);
// The compiler: a schema to one check closure, built once. Keywords group by the type they constrain, so a value
// is classified once per schema, and the common shapes (closed objects, arrays of one item schema, tagged unions,
// leaf schemas) get one specialized closure each.





                                                                                               

/** Thrown when a compile without dynamic scope tracking meets a bookended `$dynamicRef`: compile again with it. */
const RESTART = Symbol("restart");

/** One compile of a schema. Its caches and indexes are garbage once the root check is built. */
class Compiler {
	         r          ;
	         draft                 ;
	         formats                                                   ;
	/** Whether checks track the dynamic scope (the schema has a bookended `$dynamicRef`). */
	         dynamic         ;
	                 schema            ;
	                 scope          ;
	                 cache = new Map               ();
	                 annotatingCache = new Map                         ();

	constructor(schema            , options                , dynamic         ) {
		this.schema = schema;
		this.r = new _resolver_ts__WEBPACK_IMPORTED_MODULE_2__.Resolver(schema, options);
		this.draft = this.r.draft;
		this.formats = options.formats;
		this.dynamic = dynamic;
		this.scope = [];
	}

	/** The check of the root schema. */
	root()        {
		return this.check(this.schema, this.r.rootBase);
	}

	/** The check of a schema (cached by schema object; a recursive reference goes through an indirection). */
	check(node         , base        )        {
		if (node === true) return _checks_ts__WEBPACK_IMPORTED_MODULE_1__.ALWAYS;
		if (node === false) return _checks_ts__WEBPACK_IMPORTED_MODULE_1__.NEVER;
		if (!(0,_resolver_ts__WEBPACK_IMPORTED_MODULE_2__.isSchemaObject)(node))
			throw new Error(`A schema must be an object or a boolean, got ${JSON.stringify(node)}`);
		const cached = this.cache.get(node);
		if (cached !== undefined) return cached;
		const bare = this.bareType(node);
		if (bare !== undefined) return bare;
		const slot                               = { check: undefined };
		this.cache.set(node, (0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.indirect)(slot));
		const check = this.build(node, this.r.baseFor(node, base));
		slot.check = check;
		this.cache.set(node, check);
		return check;
	}

	        build(node              , base        )        {
		if (this.draft === "7" && typeof node.$ref === "string") return this.ref(node.$ref, base);
		if (this.draft !== "7" && (node.unevaluatedProperties !== undefined || node.unevaluatedItems !== undefined)) {
			return (0,_annotations_ts__WEBPACK_IMPORTED_MODULE_0__.withFreshAnnotations)(this.annotating(node, base));
		}
		const check = this.plain(node, base);
		return this.dynamic && this.r.isResource(node, base) ? (0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.scopedCheck)(this.scope, base, check) : check;
	}

	/**
	 * The module-level check of a schema that is only a `type` (with annotations), `ALWAYS` for one with no assertion
	 * at all; undefined for every other schema. These are the most common subschemas and cost nothing to share.
	 */
	        bareType(node              )                    {
		let type         ;
		for (const k in node) {
			if (k === "type") type = node.type;
			else if (!NEUTRAL_KEYWORDS.has(k)) return undefined;
		}
		if (type === undefined) return _checks_ts__WEBPACK_IMPORTED_MODULE_1__.ALWAYS;
		return typeof type === "string" ? _checks_ts__WEBPACK_IMPORTED_MODULE_1__.TYPE_CHECKS.get(type) : undefined;
	}

	/** Every keyword of `node`, not tracking annotations. */
	        plain(node              , base        )        {
		const parts          = [];
		const types = node.type === undefined ? undefined : Array.isArray(node.type) ? node.type : [node.type];
		const single = types?.length === 1 ? types[0] : undefined;
		let typeInGroup = false;
		if (single === "bytes") {
			parts.push((0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.bytesCheck)(number(node.minLength) ?? 0, number(node.maxLength) ?? Number.POSITIVE_INFINITY));
			typeInGroup = true;
		} else {
			const string = this.stringGroup(node, single === "string");
			if (string !== undefined) parts.push(string);
			const numeric = single === "number" || single === "integer";
			const number = this.numberGroup(node, single === "number" ? 1 : single === "integer" ? 2 : 0);
			if (number !== undefined) parts.push(number);
			const object = this.objectGroup(node, base, single === "object");
			if (object !== undefined) parts.push(object);
			const array = this.arrayGroup(node, base, single === "array");
			if (array !== undefined) parts.push(array);
			typeInGroup =
				(single === "string" && string !== undefined) ||
				(numeric && number !== undefined) ||
				(single === "object" && object !== undefined) ||
				(single === "array" && array !== undefined);
		}
		if (types !== undefined && !typeInGroup) parts.unshift((0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.typeCheck)(types));
		this.inPlace(node, base, parts);
		return (0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.and)(parts);
	}

	// The keywords that apply to the value itself, whatever its type.
	        inPlace(node              , base        , parts         )       {
		if (Array.isArray(node.enum)) parts.push((0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.enumCheck)(node.enum));
		if (Object.hasOwn(node, "const")) parts.push((0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.constCheck)(node.const));
		if (Array.isArray(node.allOf)) parts.push((0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.and)(node.allOf.map((s) => this.check(s, base))));
		if (Array.isArray(node.anyOf)) parts.push(this.union(node.anyOf, base, false));
		if (Array.isArray(node.oneOf)) parts.push(this.union(node.oneOf, base, true));
		if (node.not !== undefined) parts.push((0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.notCheck)(this.check(node.not, base)));
		if (node.if !== undefined && (node.then !== undefined || node.else !== undefined)) {
			const then = node.then === undefined ? _checks_ts__WEBPACK_IMPORTED_MODULE_1__.ALWAYS : this.check(node.then, base);
			const otherwise = node.else === undefined ? _checks_ts__WEBPACK_IMPORTED_MODULE_1__.ALWAYS : this.check(node.else, base);
			parts.push((0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.ifCheck)(this.check(node.if, base), then, otherwise));
		}
		if (typeof node.$ref === "string") parts.push(this.ref(node.$ref, base));
		if (this.draft !== "7" && typeof node.$dynamicRef === "string") {
			parts.push(this.dynamicRef(node.$dynamicRef, base));
		}
	}

	        union(branches                    , base        , exactlyOne         )        {
		const checks = () => branches.map((s) => this.check(s, base));
		return (
			constUnion(branches, exactlyOne) ??
			this.tagged(branches, base) ??
			(exactlyOne ? (0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.oneCheck)(checks()) : (0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.anyCheck)(checks()))
		);
	}

	        ref(ref        , base        )        {
		const target = this.r.resolve(ref, base);
		const check = this.check(target.node, target.base);
		return this.dynamic && target.base !== base ? (0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.scopedCheck)(this.scope, target.base, check) : check;
	}

	        dynamicRef(ref        , base        )        {
		const resolved = this.bookended(ref, base);
		if (resolved === undefined) return this.ref(ref, base);
		const { initial, name } = resolved;
		const fallback = this.check(initial.node, initial.base);
		const lookup = this.dynamicLookup(name, (node, b) => this.check(node, b));
		const scope = this.scope;
		return (v) => {
			for (let i = 0; i < scope.length; i++) {
				const check = lookup(scope[i]);
				if (check !== null) return check(v);
			}
			return fallback(v);
		};
	}

	/** The initial target and anchor name of a `$dynamicRef` that resolves dynamically, or undefined for a plain ref. */
	        bookended(
		ref        ,
		base        ,
	)                                                                         {
		const initial = this.r.resolve(ref, base);
		const uri = (0,_resolver_ts__WEBPACK_IMPORTED_MODULE_2__.resolveUri)(ref, base);
		const name = uri.slice((0,_resolver_ts__WEBPACK_IMPORTED_MODULE_2__.documentOf)(uri).length + 1);
		const anchored = (0,_resolver_ts__WEBPACK_IMPORTED_MODULE_2__.isSchemaObject)(initial.node) && initial.node.$dynamicAnchor === name;
		if (name === "" || name.startsWith("/") || !anchored) return undefined;
		if (!this.dynamic) throw RESTART;
		return { initial, name };
	}

	// Resolved at run time against the dynamic scope, so a schema with a bookended $dynamicRef keeps its compiler.
	        dynamicLookup   (name        , compile                                         )                            {
		const found = new Map                  ();
		return (uri) => {
			const key = `${uri}#${name}`;
			let check = found.get(key);
			if (check === undefined) {
				const target = this.r.dynamicAnchors.get(key);
				check = target === undefined ? null : compile(target, this.r.baseOf.get(target) ?? uri);
				found.set(key, check);
			}
			return check;
		};
	}

	/** anyOf/oneOf whose branches are all objects with a distinct required const on one key: the tag picks the branch. */
	        tagged(branches                    , base        )                    {
		if (branches.length < 2) return undefined;
		const resolved                 = [];
		for (const branch of branches) {
			if (!(0,_resolver_ts__WEBPACK_IMPORTED_MODULE_2__.isSchemaObject)(branch)) return undefined;
			const ref = typeof branch.$ref === "string" && Object.keys(branch).length === 1 ? branch.$ref : undefined;
			const node = ref === undefined ? branch : this.r.resolve(ref, this.r.baseFor(branch, base)).node;
			if (!(0,_resolver_ts__WEBPACK_IMPORTED_MODULE_2__.isSchemaObject)(node)) return undefined;
			resolved.push(node);
		}
		const first = resolved[0].properties;
		if (!(0,_resolver_ts__WEBPACK_IMPORTED_MODULE_2__.isSchemaObject)(first)) return undefined;
		for (const key of Object.keys(first)) {
			const tags = resolved.map((s) => tagOf(s, key));
			if (tags.some((t) => t === undefined) || new Set(tags).size !== tags.length) continue;
			return (0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.taggedCheck)(
				key,
				tags,
				branches.map((s) => this.check(s, base)),
			);
		}
		return undefined;
	}

	        stringGroup(node              , strict         )                    {
		const min = number(node.minLength) ?? 0;
		const max = number(node.maxLength) ?? Number.POSITIVE_INFINITY;
		const re = typeof node.pattern === "string" ? (0,_shared_ts__WEBPACK_IMPORTED_MODULE_3__.sharedRegex)(node.pattern) : undefined;
		const format =
			this.formats !== undefined && typeof node.format === "string" && Object.hasOwn(this.formats, node.format)
				? this.formats[node.format]
				: undefined;
		if (min === 0 && max === Number.POSITIVE_INFINITY && re === undefined && format === undefined) return undefined;
		return (0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.stringCheck)(strict, min, max, re, format);
	}

	        numberGroup(node              , mode           )                    {
		const min = number(node.minimum);
		const max = number(node.maximum);
		const xmin = number(node.exclusiveMinimum);
		const xmax = number(node.exclusiveMaximum);
		const multipleOf = number(node.multipleOf);
		if ([min, max, xmin, xmax, multipleOf].every((x) => x === undefined)) return undefined;
		const inf = Number.POSITIVE_INFINITY;
		return (0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.numberCheck)(mode, min ?? -inf, max ?? inf, xmin ?? -inf, xmax ?? inf, multipleOf);
	}

	        objectGroup(node              , base        , strict         )                    {
		const onlyCore = OBJECT_EXTRAS.every((k) => node[k] === undefined);
		// A schema with only the core object keywords gets one closure that also does the type guard.
		const core = this.objectCore(node, base, onlyCore ? (strict ? _checks_ts__WEBPACK_IMPORTED_MODULE_1__.GUARD_STRICT : _checks_ts__WEBPACK_IMPORTED_MODULE_1__.GUARD_LOOSE) : _checks_ts__WEBPACK_IMPORTED_MODULE_1__.GUARD_NONE);
		if (core !== undefined && onlyCore) return core;
		const parts          = core === undefined ? [] : [core];
		if (node.propertyNames !== undefined) parts.push((0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.propertyNamesCheck)(this.check(node.propertyNames, base)));
		const minProperties = number(node.minProperties);
		const maxProperties = number(node.maxProperties);
		if (minProperties !== undefined || maxProperties !== undefined) {
			parts.push((0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.propertyCountCheck)(minProperties ?? 0, maxProperties ?? Number.POSITIVE_INFINITY));
		}
		const dependentRequired                       = [];
		const dependentSchemas                    = [];
		if (this.draft !== "7") {
			for (const [k, list] of entries(node.dependentRequired))
				if (Array.isArray(list)) dependentRequired.push([k, list]);
			for (const [k, s] of entries(node.dependentSchemas)) dependentSchemas.push([k, this.check(s, base)]);
		}
		for (const [k, d] of entries(node.dependencies)) {
			if (Array.isArray(d)) dependentRequired.push([k, d]);
			else dependentSchemas.push([k, this.check(d, base)]);
		}
		if (dependentRequired.length > 0) parts.push((0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.dependentRequiredCheck)(dependentRequired));
		if (dependentSchemas.length > 0) parts.push((0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.dependentSchemasCheck)(dependentSchemas));
		if (parts.length === 0) return strict ? _checks_ts__WEBPACK_IMPORTED_MODULE_1__.IS_OBJECT : undefined;
		return (0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.guardObject)((0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.and)(parts), strict);
	}

	        objectCore(node              , base        , guard       )                    {
		const properties = (0,_resolver_ts__WEBPACK_IMPORTED_MODULE_2__.isSchemaObject)(node.properties) ? node.properties : {};
		const required = Array.isArray(node.required) ? node.required.filter((k) => typeof k === "string") : [];
		const patterns = (0,_resolver_ts__WEBPACK_IMPORTED_MODULE_2__.isSchemaObject)(node.patternProperties) ? node.patternProperties : {};
		const additional = node.additionalProperties;
		const keys = Object.keys(properties);
		const patternKeys = Object.keys(patterns);
		if (
			keys.length + required.length + patternKeys.length === 0 &&
			(additional === undefined || additional === true)
		) {
			return undefined;
		}
		// One array per object: key, check, flags (leaf code, required, key also names an Object.prototype member).
		const entries            = [];
		for (const k of keys) {
			const check = this.check(properties[k], base);
			const flags =
				(_checks_ts__WEBPACK_IMPORTED_MODULE_1__.LEAF_CODES.get(check) ?? 0) |
				(required.includes(k) ? _checks_ts__WEBPACK_IMPORTED_MODULE_1__.REQUIRED : 0) |
				(k in Object.prototype ? _checks_ts__WEBPACK_IMPORTED_MODULE_1__.INHERITED : 0);
			entries.push(k, check, flags);
		}
		const extraRequired = required.filter((k) => !Object.hasOwn(properties, k));
		const pats = patternKeys.map((p) => [(0,_shared_ts__WEBPACK_IMPORTED_MODULE_3__.sharedRegex)(p), this.check(patterns[p], base)]         );
		const extra = additional === undefined || additional === true ? undefined : this.check(additional, base);
		if (pats.length === 0 && extraRequired.length === 0 && (extra === undefined || additional === false)) {
			const requiredCount = keys.filter((k) => required.includes(k)).length;
			return (0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.declaredObject)(entries, requiredCount, additional === false, guard);
		}
		return (0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.openObject)(entries, keys, extraRequired, pats, extra, guard);
	}

	        arrayGroup(node              , base        , strict         )                    {
		let prefix                     = [];
		let rest         ;
		if (Array.isArray(node.items)) {
			prefix = node.items;
			rest = node.additionalItems;
		} else {
			if (this.draft !== "7" && Array.isArray(node.prefixItems)) prefix = node.prefixItems;
			rest = node.items;
		}
		const prefixChecks = prefix.map((s) => this.check(s, base));
		const restCheck = rest === undefined || rest === true ? undefined : this.check(rest, base);
		const minItems = number(node.minItems);
		const maxItems = number(node.maxItems);
		if (
			node.contains === undefined &&
			node.uniqueItems !== true &&
			(prefixChecks.length > 0 || restCheck !== undefined)
		) {
			// The common array: one closure for the guard, the length bounds and the items.
			const guard = strict ? _checks_ts__WEBPACK_IMPORTED_MODULE_1__.GUARD_STRICT : _checks_ts__WEBPACK_IMPORTED_MODULE_1__.GUARD_LOOSE;
			return (0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.listCheck)(guard, minItems ?? 0, maxItems ?? Number.POSITIVE_INFINITY, prefixChecks, restCheck);
		}
		const parts          = [];
		if (prefixChecks.length > 0 || restCheck !== undefined) parts.push((0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.itemsCheck)(prefixChecks, restCheck));
		if (minItems !== undefined || maxItems !== undefined) {
			parts.push((0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.lengthCheck)(minItems ?? 0, maxItems ?? Number.POSITIVE_INFINITY));
		}
		if (node.contains !== undefined) {
			const min = this.draft === "7" ? 1 : (number(node.minContains) ?? 1);
			const max =
				this.draft === "7" ? Number.POSITIVE_INFINITY : (number(node.maxContains) ?? Number.POSITIVE_INFINITY);
			parts.push((0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.containsCheck)(this.check(node.contains, base), min, max));
		}
		if (node.uniqueItems === true) parts.push(_checks_ts__WEBPACK_IMPORTED_MODULE_1__.uniqueCheck);
		if (parts.length === 0) return strict ? _checks_ts__WEBPACK_IMPORTED_MODULE_1__.IS_ARRAY : undefined;
		return (0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.guardArray)((0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.and)(parts), strict);
	}

	/** The annotating check of a schema evaluated in place (only below `unevaluatedProperties`/`unevaluatedItems`). */
	annotating(node         , base        )                  {
		if (node === true) return _annotations_ts__WEBPACK_IMPORTED_MODULE_0__.ANNOTATING_TRUE;
		if (node === false) return _annotations_ts__WEBPACK_IMPORTED_MODULE_0__.ANNOTATING_FALSE;
		if (!(0,_resolver_ts__WEBPACK_IMPORTED_MODULE_2__.isSchemaObject)(node))
			throw new Error(`A schema must be an object or a boolean, got ${JSON.stringify(node)}`);
		const cached = this.annotatingCache.get(node);
		if (cached !== undefined) return cached;
		const slot                                         = { check: undefined };
		this.annotatingCache.set(node, (0,_annotations_ts__WEBPACK_IMPORTED_MODULE_0__.indirectAnnotating)(slot));
		const check = this.buildAnnotating(node, this.r.baseFor(node, base));
		slot.check = check;
		this.annotatingCache.set(node, check);
		return check;
	}

	        buildAnnotating(node              , base        )                  {
		const rest                          = {};
		let annotates = false;
		for (const k of Object.keys(node)) {
			if (ANNOTATING.has(k)) annotates = true;
			else rest[k] = node[k];
		}
		const plain = this.plain(rest, base);
		const parts                    = [];
		if (annotates) this.annotatingParts(node, base, parts);
		const all = parts.length === 0 ? (0,_annotations_ts__WEBPACK_IMPORTED_MODULE_0__.ignoreAnnotations)(plain) : (0,_annotations_ts__WEBPACK_IMPORTED_MODULE_0__.annotateAll)(plain, parts);
		const own =
			node.unevaluatedProperties !== undefined || node.unevaluatedItems !== undefined
				? (0,_annotations_ts__WEBPACK_IMPORTED_MODULE_0__.isolateAnnotations)(all)
				: all;
		return this.dynamic && this.r.isResource(node, base) ? (0,_annotations_ts__WEBPACK_IMPORTED_MODULE_0__.scopedAnnotating)(this.scope, base, own) : own;
	}

	        annotatingParts(node              , base        , parts                   )       {
		if (
			node.properties !== undefined ||
			node.patternProperties !== undefined ||
			node.additionalProperties !== undefined
		) {
			const props = entries(node.properties).map(([k, s]) => [k, this.check(s, base)]         );
			const pats = entries(node.patternProperties).map(([p, s]) => [(0,_shared_ts__WEBPACK_IMPORTED_MODULE_3__.sharedRegex)(p), this.check(s, base)]         );
			const extra =
				node.additionalProperties === undefined ? undefined : this.check(node.additionalProperties, base);
			parts.push((0,_annotations_ts__WEBPACK_IMPORTED_MODULE_0__.annotateObject)(props, pats, extra));
		}
		if (node.prefixItems !== undefined || node.items !== undefined || node.contains !== undefined) {
			const prefix = Array.isArray(node.prefixItems)
				? node.prefixItems
				: Array.isArray(node.items)
					? node.items
					: [];
			const rest = Array.isArray(node.items) ? node.additionalItems : node.items;
			parts.push(
				(0,_annotations_ts__WEBPACK_IMPORTED_MODULE_0__.annotateArray)(
					prefix.map((s) => this.check(s, base)),
					rest === undefined ? undefined : this.check(rest, base),
					node.contains === undefined ? undefined : this.check(node.contains, base),
					number(node.minContains) ?? 1,
					number(node.maxContains) ?? Number.POSITIVE_INFINITY,
				),
			);
		}
		if (Array.isArray(node.allOf)) for (const s of node.allOf) parts.push(this.annotating(s, base));
		if (Array.isArray(node.anyOf))
			parts.push(
				(0,_annotations_ts__WEBPACK_IMPORTED_MODULE_0__.annotateAny)(
					node.anyOf.map((s) => this.annotating(s, base)),
					false,
				),
			);
		if (Array.isArray(node.oneOf))
			parts.push(
				(0,_annotations_ts__WEBPACK_IMPORTED_MODULE_0__.annotateAny)(
					node.oneOf.map((s) => this.annotating(s, base)),
					true,
				),
			);
		if (node.if !== undefined) {
			const then = node.then === undefined ? undefined : this.annotating(node.then, base);
			const otherwise = node.else === undefined ? undefined : this.annotating(node.else, base);
			parts.push((0,_annotations_ts__WEBPACK_IMPORTED_MODULE_0__.annotateIf)(this.annotating(node.if, base), then, otherwise));
		}
		if (typeof node.$ref === "string") parts.push(this.annotatingRef(node.$ref, base));
		if (typeof node.$dynamicRef === "string") parts.push(this.annotatingDynamicRef(node.$dynamicRef, base));
		for (const [k, s] of entries(node.dependentSchemas)) parts.push((0,_annotations_ts__WEBPACK_IMPORTED_MODULE_0__.annotateDependent)(k, this.annotating(s, base)));
		if (node.unevaluatedProperties !== undefined) {
			parts.push((0,_annotations_ts__WEBPACK_IMPORTED_MODULE_0__.unevaluatedProperties)(this.check(node.unevaluatedProperties, base)));
		}
		if (node.unevaluatedItems !== undefined) parts.push((0,_annotations_ts__WEBPACK_IMPORTED_MODULE_0__.unevaluatedItems)(this.check(node.unevaluatedItems, base)));
	}

	        annotatingRef(ref        , base        )                  {
		const target = this.r.resolve(ref, base);
		const check = this.annotating(target.node, target.base);
		return this.dynamic && target.base !== base ? (0,_annotations_ts__WEBPACK_IMPORTED_MODULE_0__.scopedAnnotating)(this.scope, target.base, check) : check;
	}

	        annotatingDynamicRef(ref        , base        )                  {
		const resolved = this.bookended(ref, base);
		if (resolved === undefined) return this.annotatingRef(ref, base);
		const { initial, name } = resolved;
		const fallback = this.annotating(initial.node, initial.base);
		const lookup = this.dynamicLookup(name, (node, b) => this.annotating(node, b));
		const scope = this.scope;
		return (v, a) => {
			for (let i = 0; i < scope.length; i++) {
				const check = lookup(scope[i]);
				if (check !== null) return check(v, a);
			}
			return fallback(v, a);
		};
	}
}

/** Keywords that never affect a check. */
const NEUTRAL_KEYWORDS = new Set([
	"$schema",
	"$comment",
	"title",
	"description",
	"default",
	"examples",
	"deprecated",
	"readOnly",
	"writeOnly",
	"$defs",
	"definitions",
	"contentEncoding",
	"contentMediaType",
	"minContains",
	"maxContains",
	"$vocabulary",
]);

// The object keywords besides the core (properties, required, patternProperties, additionalProperties).
const OBJECT_EXTRAS = [
	"propertyNames",
	"minProperties",
	"maxProperties",
	"dependentRequired",
	"dependentSchemas",
	"dependencies",
];

// The keywords an annotating check handles itself; every other keyword compiles into its plain part.
const ANNOTATING = new Set([
	"properties",
	"patternProperties",
	"additionalProperties",
	"prefixItems",
	"items",
	"additionalItems",
	"contains",
	"minContains",
	"maxContains",
	"allOf",
	"anyOf",
	"oneOf",
	"if",
	"then",
	"else",
	"$ref",
	"$dynamicRef",
	"dependentSchemas",
	"unevaluatedProperties",
	"unevaluatedItems",
]);

/** anyOf/oneOf whose branches are all `{ const: primitive }`: an enum (distinct values make oneOf the same). */
function constUnion(branches                    , exactlyOne         )                    {
	const values            = [];
	for (const branch of branches) {
		if (!(0,_resolver_ts__WEBPACK_IMPORTED_MODULE_2__.isSchemaObject)(branch) || !Object.hasOwn(branch, "const")) return undefined;
		for (const k of Object.keys(branch)) if (k !== "const" && !NEUTRAL_KEYWORDS.has(k)) return undefined;
		if (typeof branch.const === "object" && branch.const !== null) return undefined;
		if (exactlyOne && values.includes(branch.const)) return undefined;
		values.push(branch.const);
	}
	return (0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.enumCheck)(values);
}

// The tag of a tagged-union branch: a primitive const on a required property of an object schema.
function tagOf(branch              , key        )          {
	const properties = branch.properties;
	if (branch.type !== "object" || !Array.isArray(branch.required) || !branch.required.includes(key)) return undefined;
	const tag = (0,_resolver_ts__WEBPACK_IMPORTED_MODULE_2__.isSchemaObject)(properties) ? properties[key] : undefined;
	if (!(0,_resolver_ts__WEBPACK_IMPORTED_MODULE_2__.isSchemaObject)(tag) || !Object.hasOwn(tag, "const")) return undefined;
	return typeof tag.const === "object" && tag.const !== null ? undefined : tag.const;
}

function number(value         )                     {
	return typeof value === "number" ? value : undefined;
}

function entries(value         )                      {
	return (0,_resolver_ts__WEBPACK_IMPORTED_MODULE_2__.isSchemaObject)(value) ? Object.entries(value) : [];
}


/***/ }),
/* 14 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   ANNOTATING_FALSE: () => (/* binding */ ANNOTATING_FALSE),
/* harmony export */   ANNOTATING_TRUE: () => (/* binding */ ANNOTATING_TRUE),
/* harmony export */   annotateAll: () => (/* binding */ annotateAll),
/* harmony export */   annotateAny: () => (/* binding */ annotateAny),
/* harmony export */   annotateArray: () => (/* binding */ annotateArray),
/* harmony export */   annotateDependent: () => (/* binding */ annotateDependent),
/* harmony export */   annotateIf: () => (/* binding */ annotateIf),
/* harmony export */   annotateObject: () => (/* binding */ annotateObject),
/* harmony export */   ignoreAnnotations: () => (/* binding */ ignoreAnnotations),
/* harmony export */   indirectAnnotating: () => (/* binding */ indirectAnnotating),
/* harmony export */   isolateAnnotations: () => (/* binding */ isolateAnnotations),
/* harmony export */   newAnnotations: () => (/* binding */ newAnnotations),
/* harmony export */   scopedAnnotating: () => (/* binding */ scopedAnnotating),
/* harmony export */   unevaluatedItems: () => (/* binding */ unevaluatedItems),
/* harmony export */   unevaluatedProperties: () => (/* binding */ unevaluatedProperties),
/* harmony export */   withFreshAnnotations: () => (/* binding */ withFreshAnnotations)
/* harmony export */ });
/* harmony import */ var _checks_ts__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(15);
// Annotation tracking for `unevaluatedProperties` and `unevaluatedItems`: annotating checks record which
// properties and items a schema evaluated. Only schemas that use those keywords compile into these closures.


                                        

/** What a schema evaluated: property names (or all of them), the evaluated item prefix, items matched by `contains`. */
                              
	                          
	                  
	              
	                  
	                            
 

/** A check that records what it evaluated into `a` when it passes. */
                                                                          

/** Empty annotations. */
function newAnnotations()              {
	return { props: null, allProps: false, items: 0, allItems: false, indexes: null };
}

function merge(a             , b             )       {
	if (b.props !== null) for (const k of b.props) addProp(a, k);
	a.allProps ||= b.allProps;
	if (b.items > a.items) a.items = b.items;
	a.allItems ||= b.allItems;
	if (b.indexes !== null) for (const i of b.indexes) addIndex(a, i);
}

function addProp(a             , key        )       {
	if (a.props === null) a.props = new Set();
	a.props.add(key);
}

function addIndex(a             , index        )       {
	if (a.indexes === null) a.indexes = new Set();
	a.indexes.add(index);
}

const ANNOTATING_TRUE                  = () => true;
const ANNOTATING_FALSE                  = () => false;

/** The indirection a recursive reference goes through. */
function indirectAnnotating(slot                                        )                  {
	return (v, a) => (slot.check                   )(v, a);
}

/** A plain check in annotating position: it evaluates nothing that unevaluated* sees. */
function ignoreAnnotations(check       )                  {
	return (v) => check(v);
}

/** An annotating check run as a plain check, with fresh annotations. */
function withFreshAnnotations(check                 )        {
	return (v) => check(v, newAnnotations());
}

/** `check` with `uri` pushed onto the dynamic scope while it runs. */
function scopedAnnotating(scope          , uri        , check                 )                  {
	return (v, a) => {
		scope.push(uri);
		const ok = check(v, a);
		scope.pop();
		return ok;
	};
}

/** unevaluated* sees only the annotations of its own schema, never those of its siblings in the parent. */
function isolateAnnotations(check                 )                  {
	return (v, a) => {
		const b = newAnnotations();
		if (!check(v, b)) return false;
		merge(a, b);
		return true;
	};
}

/** The non-annotating keywords, then each annotating part in order. */
function annotateAll(plain       , parts                            )                  {
	return (v, a) => {
		if (!plain(v)) return false;
		for (let i = 0; i < parts.length; i++) if (!parts[i](v, a)) return false;
		return true;
	};
}

/** `properties`, `patternProperties` and `additionalProperties`. */
function annotateObject(
	props                                       ,
	patterns                                       ,
	additional                   ,
)                  {
	const declared = new Set(props.map(([k]) => k));
	return (v, a) => {
		if (!(0,_checks_ts__WEBPACK_IMPORTED_MODULE_0__.isObject)(v)) return true;
		const seen           = [];
		for (const [k, check] of props) {
			if (!Object.hasOwn(v, k)) continue;
			if (!check(v[k])) return false;
			seen.push(k);
		}
		for (const k of Object.keys(v)) {
			let matched = declared.has(k);
			for (const [re, check] of patterns) {
				if (re.test(k)) {
					if (!check(v[k])) return false;
					matched = true;
					seen.push(k);
				}
			}
			if (!matched && additional !== undefined) {
				if (!additional(v[k])) return false;
				seen.push(k);
			}
		}
		for (const k of seen) addProp(a, k);
		return true;
	};
}

/** `prefixItems`/`items`/`additionalItems` and `contains` with its bounds. */
function annotateArray(
	prefix                  ,
	rest                   ,
	contains                   ,
	minContains        ,
	maxContains        ,
)                  {
	return (v, a) => {
		if (!Array.isArray(v)) return true;
		const m = Math.min(v.length, prefix.length);
		for (let i = 0; i < m; i++) if (!prefix[i](v[i])) return false;
		if (rest !== undefined) {
			for (let i = prefix.length; i < v.length; i++) if (!rest(v[i])) return false;
			a.allItems = true;
		}
		if (m > a.items) a.items = m;
		if (contains !== undefined) {
			let n = 0;
			for (let i = 0; i < v.length; i++) {
				if (contains(v[i])) {
					n++;
					addIndex(a, i);
				}
			}
			if (n < minContains || n > maxContains) return false;
		}
		return true;
	};
}

/** `anyOf` (or `oneOf` when `exactlyOne`): every passing branch contributes its annotations. */
function annotateAny(branches                            , exactlyOne         )                  {
	return (v, a) => {
		let n = 0;
		for (const branch of branches) {
			const b = newAnnotations();
			if (branch(v, b)) {
				n++;
				merge(a, b);
			}
		}
		return exactlyOne ? n === 1 : n > 0;
	};
}

/** `if`, `then` and `else`: a passing `if` contributes its annotations even without `then`. */
function annotateIf(
	cond                 ,
	then                             ,
	otherwise                             ,
)                  {
	return (v, a) => {
		const b = newAnnotations();
		if (cond(v, b)) {
			merge(a, b);
			return then === undefined || then(v, a);
		}
		return otherwise === undefined || otherwise(v, a);
	};
}

/** One `dependentSchemas` entry. */
function annotateDependent(key        , check                 )                  {
	return (v, a) => !(0,_checks_ts__WEBPACK_IMPORTED_MODULE_0__.isObject)(v) || !Object.hasOwn(v, key) || check(v, a);
}

/** `unevaluatedProperties`: every property no other keyword evaluated passes `check`. */
function unevaluatedProperties(check       )                  {
	return (v, a) => {
		if (!(0,_checks_ts__WEBPACK_IMPORTED_MODULE_0__.isObject)(v) || a.allProps) return true;
		for (const k of Object.keys(v)) if (!(a.props?.has(k) ?? false) && !check(v[k])) return false;
		a.allProps = true;
		return true;
	};
}

/** `unevaluatedItems`: every item no other keyword evaluated passes `check`. */
function unevaluatedItems(check       )                  {
	return (v, a) => {
		if (!Array.isArray(v) || a.allItems) return true;
		for (let i = a.items; i < v.length; i++) if (!(a.indexes?.has(i) ?? false) && !check(v[i])) return false;
		a.allItems = true;
		return true;
	};
}


/***/ }),
/* 15 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   ALWAYS: () => (/* binding */ ALWAYS),
/* harmony export */   CODE: () => (/* binding */ CODE),
/* harmony export */   GUARD_LOOSE: () => (/* binding */ GUARD_LOOSE),
/* harmony export */   GUARD_NONE: () => (/* binding */ GUARD_NONE),
/* harmony export */   GUARD_STRICT: () => (/* binding */ GUARD_STRICT),
/* harmony export */   INHERITED: () => (/* binding */ INHERITED),
/* harmony export */   IS_ARRAY: () => (/* binding */ IS_ARRAY),
/* harmony export */   IS_OBJECT: () => (/* binding */ IS_OBJECT),
/* harmony export */   LEAF_CODES: () => (/* binding */ LEAF_CODES),
/* harmony export */   NEVER: () => (/* binding */ NEVER),
/* harmony export */   REQUIRED: () => (/* binding */ REQUIRED),
/* harmony export */   TYPE_CHECKS: () => (/* binding */ TYPE_CHECKS),
/* harmony export */   and: () => (/* binding */ and),
/* harmony export */   anyCheck: () => (/* binding */ anyCheck),
/* harmony export */   bytesCheck: () => (/* binding */ bytesCheck),
/* harmony export */   codePoints: () => (/* binding */ codePoints),
/* harmony export */   constCheck: () => (/* binding */ constCheck),
/* harmony export */   containsCheck: () => (/* binding */ containsCheck),
/* harmony export */   declaredObject: () => (/* binding */ declaredObject),
/* harmony export */   deepEqual: () => (/* binding */ deepEqual),
/* harmony export */   dependentRequiredCheck: () => (/* binding */ dependentRequiredCheck),
/* harmony export */   dependentSchemasCheck: () => (/* binding */ dependentSchemasCheck),
/* harmony export */   enumCheck: () => (/* binding */ enumCheck),
/* harmony export */   guardArray: () => (/* binding */ guardArray),
/* harmony export */   guardObject: () => (/* binding */ guardObject),
/* harmony export */   ifCheck: () => (/* binding */ ifCheck),
/* harmony export */   indirect: () => (/* binding */ indirect),
/* harmony export */   isMultiple: () => (/* binding */ isMultiple),
/* harmony export */   isObject: () => (/* binding */ isObject),
/* harmony export */   itemsCheck: () => (/* binding */ itemsCheck),
/* harmony export */   lengthCheck: () => (/* binding */ lengthCheck),
/* harmony export */   listCheck: () => (/* binding */ listCheck),
/* harmony export */   notCheck: () => (/* binding */ notCheck),
/* harmony export */   numberCheck: () => (/* binding */ numberCheck),
/* harmony export */   oneCheck: () => (/* binding */ oneCheck),
/* harmony export */   openObject: () => (/* binding */ openObject),
/* harmony export */   propertyCountCheck: () => (/* binding */ propertyCountCheck),
/* harmony export */   propertyNamesCheck: () => (/* binding */ propertyNamesCheck),
/* harmony export */   scopedCheck: () => (/* binding */ scopedCheck),
/* harmony export */   stringCheck: () => (/* binding */ stringCheck),
/* harmony export */   taggedCheck: () => (/* binding */ taggedCheck),
/* harmony export */   typeCheck: () => (/* binding */ typeCheck),
/* harmony export */   uniqueCheck: () => (/* binding */ uniqueCheck)
/* harmony export */ });
// Check factories: each returns a closure that captures only its arguments, never the compiler, so a compiled
// schema keeps no compile-time state alive. Leaf checks are module constants that object and array loops inline.

                                        

const ALWAYS        = () => true;
const NEVER        = () => false;
const IS_STRING        = (v) => typeof v === "string";
const IS_NUMBER        = (v) => typeof v === "number";
const IS_INTEGER        = (v) => Number.isInteger(v);
const IS_BOOLEAN        = (v) => typeof v === "boolean";
const IS_NULL        = (v) => v === null;
const IS_ARRAY        = (v) => Array.isArray(v);
const IS_BYTES        = (v) => v instanceof Uint8Array;
const IS_OBJECT        = (v) => isObject(v);

/** Whether `v` is a JSON object: not null, an array or bytes. */
function isObject(v         )                               {
	return typeof v === "object" && v !== null && !Array.isArray(v) && !(v instanceof Uint8Array);
}

/** The check of a `type` name; `bytes` (a Uint8Array) extends JSON Schema for values that never pass through JSON. */
const TYPE_CHECKS                             = new Map([
	["string", IS_STRING],
	["number", IS_NUMBER],
	["integer", IS_INTEGER],
	["boolean", IS_BOOLEAN],
	["null", IS_NULL],
	["array", IS_ARRAY],
	["object", IS_OBJECT],
	["bytes", IS_BYTES],
]);

/** Leaf checks that object and array loops run inline by code instead of calling them. */
const LEAF_CODES                             = new Map([
	[IS_STRING, 1],
	[IS_NUMBER, 2],
	[IS_INTEGER, 3],
	[IS_BOOLEAN, 4],
	[IS_NULL, 5],
	[ALWAYS, 6],
]);

function leafOk(code        , x         )          {
	switch (code) {
		case 1:
			return typeof x === "string";
		case 2:
			return typeof x === "number";
		case 3:
			return Number.isInteger(x);
		case 4:
			return typeof x === "boolean";
		case 5:
			return x === null;
		default:
			return true;
	}
}

/** Whether a combined object or array check also checks the type: not at all, strictly, or passing other types. */
const GUARD_NONE = 0;
const GUARD_STRICT = 1;
const GUARD_LOOSE = 2;
                                                                                 

/** Flags of a declared property in an object check's entries: its leaf code and two bits. */
const CODE = 7;
const REQUIRED = 8;
const INHERITED = 16;

/** All of `parts`; two and three parts get closures without a loop. */
function and(parts                  )        {
	switch (parts.length) {
		case 0:
			return ALWAYS;
		case 1:
			return parts[0];
		case 2: {
			const [a, b] = parts;
			return (v) => a(v) && b(v);
		}
		case 3: {
			const [a, b, c] = parts;
			return (v) => a(v) && b(v) && c(v);
		}
		default:
			return (v) => {
				for (let i = 0; i < parts.length; i++) if (!parts[i](v)) return false;
				return true;
			};
	}
}

/** A check whose implementation is set later: the indirection a recursive reference goes through. */
function indirect(slot                              )        {
	return (v) => (slot.check         )(v);
}

/** `check` with `uri` pushed onto the dynamic scope while it runs (only for schemas with `$dynamicRef`). */
function scopedCheck(scope          , uri        , check       )        {
	return (v) => {
		scope.push(uri);
		const ok = check(v);
		scope.pop();
		return ok;
	};
}

/** Any of the named types. */
function typeCheck(types                    )        {
	const checks = types.map((t) => (typeof t === "string" ? TYPE_CHECKS.get(t) : undefined) ?? NEVER);
	if (checks.length === 1) return checks[0];
	return (v) => {
		for (let i = 0; i < checks.length; i++) if (checks[i](v)) return true;
		return false;
	};
}

/** A Uint8Array within the length bounds. */
function bytesCheck(min        , max        )        {
	return (v) => v instanceof Uint8Array && v.length >= min && v.length <= max;
}

/** The number of Unicode code points of `s`: JSON Schema string lengths count code points, not UTF-16 units. */
function codePoints(s        )         {
	let n = 0;
	for (let i = 0; i < s.length; i++, n++) {
		const c = s.charCodeAt(i);
		// A high surrogate starts a pair only when a low surrogate follows; an unpaired one is a code point of its own.
		if (c >= 0xd800 && c <= 0xdbff && i + 1 < s.length) {
			const next = s.charCodeAt(i + 1);
			if (next >= 0xdc00 && next <= 0xdfff) i++;
		}
	}
	return n;
}

// UTF-16 units bound code points from both sides (units / 2 <= points <= units), so most strings need no count.
function lengthOk(s        , min        , max        )          {
	const units = s.length;
	if (units < min) return false;
	if (units < 2 * min && codePoints(s) < min) return false;
	return units <= max || codePoints(s) <= max;
}

/** The string keywords: length bounds, pattern and format. */
function stringCheck(
	strict         ,
	min        ,
	max        ,
	re                    ,
	format                                          ,
)        {
	if (re !== undefined && min === 0 && max === Number.POSITIVE_INFINITY && format === undefined) {
		return strict ? (v) => typeof v === "string" && re.test(v) : (v) => typeof v !== "string" || re.test(v);
	}
	if (re === undefined && format === undefined) {
		return strict
			? (v) => typeof v === "string" && lengthOk(v, min, max)
			: (v) => typeof v !== "string" || lengthOk(v, min, max);
	}
	return (v) => {
		if (typeof v !== "string") return !strict;
		return lengthOk(v, min, max) && (re === undefined || re.test(v)) && (format === undefined || format(v));
	};
}

/** The number keywords. `mode`: 0 passes other types, 1 requires a number, 2 an integer. */
function numberCheck(
	mode           ,
	min        ,
	max        ,
	xmin        ,
	xmax        ,
	multipleOf                    ,
)        {
	return (v) => {
		if (typeof v !== "number") return mode === 0;
		if (mode === 2 && !Number.isInteger(v)) return false;
		return v >= min && v <= max && v > xmin && v < xmax && (multipleOf === undefined || isMultiple(v, multipleOf));
	};
}

/** Whether `v` is a multiple of `d`, exactly for decimals: 0.0075 / 0.0001 is 74.99999999999999 in floating point. */
function isMultiple(v        , d        )          {
	const q = v / d;
	if (Number.isInteger(q)) return true;
	if (!Number.isFinite(q)) return v % d === 0;
	const [vc, ve] = decimal(v);
	const [dc, de] = decimal(d);
	return ve >= de ? (vc * 10n ** BigInt(ve - de)) % dc === 0n : vc % (dc * 10n ** BigInt(de - ve)) === 0n;
}

function decimal(x        )                   {
	const [mantissa, exponent = "0"] = Math.abs(x).toString().split("e");
	const point = mantissa.indexOf(".");
	const fraction = point < 0 ? 0 : mantissa.length - point - 1;
	return [BigInt(mantissa.replace(".", "")), Number(exponent) - fraction];
}

/** `inner` for objects; other values pass unless `strict`. */
function guardObject(inner       , strict         )        {
	return strict ? (v) => isObject(v) && inner(v) : (v) => !isObject(v) || inner(v);
}

/** `inner` for arrays; other values pass unless `strict`. */
function guardArray(inner       , strict         )        {
	return strict ? (v) => Array.isArray(v) && inner(v) : (v) => !Array.isArray(v) || inner(v);
}

/**
 * An object with declared properties: `entries` is `[key, check, flags]` per property. The value's own keys are
 * walked with for...in (whose `o[k]` V8 reads from the enum cache, unlike a load by a name from a list), each found
 * among the declared names; `closed` rejects any other key. A property whose value is `undefined` counts as absent.
 */
function declaredObject(
	entries                    ,
	requiredCount        ,
	closed         ,
	guard       ,
)        {
	const n = entries.length;
	return (v) => {
		if (guard !== GUARD_NONE && !isObject(v)) return guard === GUARD_LOOSE;
		// Guarded: by the check above, or by the caller for GUARD_NONE.
		const o = v                           ;
		let required = 0;
		for (const k in o) {
			let i = 0;
			while (i < n && entries[i] !== k) i += 3;
			if (i === n) {
				if (closed) return false;
				continue;
			}
			const x = o[k];
			if (x === undefined) continue;
			const f = entries[i + 2]          ;
			const c = f & CODE;
			if (c !== 0 ? !leafOk(c, x) : !(entries[i + 1]         )(x)) return false;
			if (f & REQUIRED) required++;
		}
		return required === requiredCount;
	};
}

/** An object with declared properties, required keys not declared, pattern properties and additional properties. */
function openObject(
	entries                    ,
	keys                   ,
	extraRequired                   ,
	patterns                                       ,
	additional                   ,
	guard       ,
)        {
	const known = patterns.length > 0 || additional !== undefined ? new Set(keys) : undefined;
	return (v) => {
		if (guard !== GUARD_NONE && !isObject(v)) return guard === GUARD_LOOSE;
		const o = v                           ;
		for (let i = 0; i < entries.length; i += 3) {
			const k = entries[i]          ;
			const x = o[k];
			const f = entries[i + 2]          ;
			if (x === undefined || (f & INHERITED && !Object.hasOwn(o, k))) {
				if (f & REQUIRED) return false;
				continue;
			}
			const c = f & CODE;
			if (c !== 0 ? !leafOk(c, x) : !(entries[i + 1]         )(x)) return false;
		}
		for (let i = 0; i < extraRequired.length; i++) if (!Object.hasOwn(o, extraRequired[i])) return false;
		if (known === undefined) return true;
		for (const k of Object.keys(o)) {
			let matched = known.has(k);
			for (let i = 0; i < patterns.length; i++) {
				if (patterns[i][0].test(k)) {
					matched = true;
					if (!patterns[i][1](o[k])) return false;
				}
			}
			if (!matched && additional !== undefined && !additional(o[k])) return false;
		}
		return true;
	};
}

/** Every property name passes `check` (`v` is an object). */
function propertyNamesCheck(check       )        {
	return (v) => {
		for (const k in v          ) if (!check(k)) return false;
		return true;
	};
}

/** `minProperties` and `maxProperties` (`v` is an object). */
function propertyCountCheck(min        , max        )        {
	return (v) => {
		let n = 0;
		for (const _ in v          ) n++;
		return n >= min && n <= max;
	};
}

/** `dependentRequired` (`v` is an object). */
function dependentRequiredCheck(entries                                                   )        {
	return (v) => {
		const o = v          ;
		for (const [k, list] of entries)
			if (Object.hasOwn(o, k)) for (const r of list) if (!Object.hasOwn(o, r)) return false;
		return true;
	};
}

/** `dependentSchemas` (`v` is an object). */
function dependentSchemasCheck(entries                                       )        {
	return (v) => {
		for (const [k, check] of entries) if (Object.hasOwn(v          , k) && !check(v)) return false;
		return true;
	};
}

/** The common array: guard, length bounds, prefix items and the items after them, in one closure. */
function listCheck(
	guard       ,
	min        ,
	max        ,
	prefix                  ,
	rest                   ,
)        {
	const n = prefix.length;
	const code = rest === undefined ? 0 : (LEAF_CODES.get(rest) ?? 0);
	return (v) => {
		if (!Array.isArray(v)) return guard === GUARD_LOOSE;
		const length = v.length;
		if (length < min || length > max) return false;
		const m = length < n ? length : n;
		for (let i = 0; i < m; i++) if (!prefix[i](v[i])) return false;
		if (rest === undefined) return true;
		if (code !== 0) {
			for (let i = n; i < length; i++) if (!leafOk(code, v[i])) return false;
			return true;
		}
		for (let i = n; i < length; i++) if (!rest(v[i])) return false;
		return true;
	};
}

/** Prefix items and the items after them (`v` is an array). */
function itemsCheck(prefix                  , rest                   )        {
	return listCheck(GUARD_NONE, 0, Number.POSITIVE_INFINITY, prefix, rest);
}

/** `minItems` and `maxItems` (`v` is an array). */
function lengthCheck(min        , max        )        {
	return (v) => (v             ).length >= min && (v             ).length <= max;
}

/** `contains` with its bounds (`v` is an array). */
function containsCheck(check       , min        , max        )        {
	return (v) => {
		const list = v             ;
		let n = 0;
		for (let i = 0; i < list.length; i++) if (check(list[i]) && ++n > max) return false;
		return n >= min;
	};
}

/** `uniqueItems` (`v` is an array). */
function uniqueCheck(v         )          {
	const list = v             ;
	for (let i = 1; i < list.length; i++) for (let j = 0; j < i; j++) if (deepEqual(list[i], list[j])) return false;
	return true;
}

/** `enum`: up to 8 primitives by a scan, more by a Set, objects and arrays by deep equality. */
function enumCheck(values                    )        {
	const primitives = values.filter((x) => typeof x !== "object" || x === null);
	if (primitives.length === values.length && primitives.length <= 8) {
		return (v) => {
			for (let i = 0; i < primitives.length; i++) if (primitives[i] === v) return true;
			return false;
		};
	}
	const set = new Set(primitives);
	const objects = values.filter((x) => typeof x === "object" && x !== null);
	return (v) => set.has(v) || (typeof v === "object" && objects.some((o) => deepEqual(v, o)));
}

/** `const`. */
function constCheck(value         )        {
	if (typeof value !== "object" || value === null) return (v) => v === value;
	return (v) => deepEqual(v, value);
}

/** `anyOf`. */
function anyCheck(checks                  )        {
	return (v) => {
		for (let i = 0; i < checks.length; i++) if (checks[i](v)) return true;
		return false;
	};
}

/** `oneOf`. */
function oneCheck(checks                  )        {
	return (v) => {
		let n = 0;
		for (let i = 0; i < checks.length; i++) if (checks[i](v) && ++n > 1) return false;
		return n === 1;
	};
}

/** `not`. */
function notCheck(check       )        {
	return (v) => !check(v);
}

/** `if`, `then` and `else`. */
function ifCheck(cond       , then       , otherwise       )        {
	return (v) => (cond(v) ? then(v) : otherwise(v));
}

/** A union of objects tagged by a const on `key`: the tag selects the one branch that can match. */
function taggedCheck(key        , tags                    , checks                  )        {
	return (v) => {
		if (!isObject(v)) return false;
		const tag = v[key];
		for (let i = 0; i < tags.length; i++) if (tags[i] === tag) return checks[i](v);
		return false;
	};
}

/** JSON equality: same primitives, arrays with equal items in order, objects with the same keys and equal values. */
function deepEqual(a         , b         )          {
	if (a === b) return true;
	if (typeof a !== "object" || typeof b !== "object" || a === null || b === null) return false;
	const array = Array.isArray(a);
	if (array !== Array.isArray(b)) return false;
	if (array) {
		const x = a             ;
		const y = b             ;
		if (x.length !== y.length) return false;
		for (let i = 0; i < x.length; i++) if (!deepEqual(x[i], y[i])) return false;
		return true;
	}
	const x = a                           ;
	const y = b                           ;
	const keys = Object.keys(x);
	if (keys.length !== Object.keys(y).length) return false;
	for (const k of keys) if (!Object.hasOwn(y, k) || !deepEqual(x[k], y[k])) return false;
	return true;
}


/***/ }),
/* 16 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   Explainer: () => (/* binding */ Explainer)
/* harmony export */ });
/* harmony import */ var _annotations_ts__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(14);
/* harmony import */ var _checks_ts__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(15);
/* harmony import */ var _resolver_ts__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(11);
/* harmony import */ var _shared_ts__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(12);
// Errors of a failed value: a walk over the schema that reports each failing keyword at the value it failed on.
// It runs only for values that failed the check, using the compiler's checks for subschemas.



                                              


                                                                

/** Explains failures of one compiled schema. */
class Explainer {
	                 c          ;
	                 schema         ;
	/**
	 * The errors of `$ref` targets for object values, within one errors() call: a recursive union explains each branch
	 * to pick the one to blame, and every branch would explain the same children again, exponentially in the depth.
	 */
	        memo                                                                                    = undefined;

	constructor(compiler          , schema         ) {
		this.c = compiler;
		this.schema = schema;
		compiler.root();
	}

	/** The errors of `value`, at least one (`value` failed the check). */
	errors(value         )                    {
		const out                    = [];
		try {
			this.walk(this.schema, this.c.r.rootBase, value, "", out);
		} finally {
			this.memo = undefined;
		}
		if (out.length === 0) out.push({ path: "", keyword: "schema", message: "invalid" });
		return out;
	}

	        ok(node         , base        , v         )          {
		return this.c.check(node, base)(v);
	}

	// For $dynamicRef schemas this check runs outside the original dynamic scope, so their errors are approximate.
	        walk(node         , base        , v         , path        , out                   )       {
		if (node === true) return;
		if (node === false) {
			out.push({ path, keyword: "false", message: "no value is allowed here" });
			return;
		}
		if (!(0,_resolver_ts__WEBPACK_IMPORTED_MODULE_2__.isSchemaObject)(node)) return;
		const b = this.c.r.baseFor(node, base);
		if (this.ok(node, b, v)) return;
		const start = out.length;
		const push = (keyword        , message        , at = path) => {
			out.push({ path: at, keyword, message });
		};
		if (this.c.draft === "7" && typeof node.$ref === "string") {
			this.walkRef(node.$ref, b, v, path, out);
			return;
		}
		if (node.type !== undefined) {
			const types = Array.isArray(node.type) ? node.type : [node.type];
			const checks = types.map((t) => (typeof t === "string" ? _checks_ts__WEBPACK_IMPORTED_MODULE_1__.TYPE_CHECKS.get(t) : undefined) ?? _checks_ts__WEBPACK_IMPORTED_MODULE_1__.NEVER);
			if (!checks.some((check) => check(v))) {
				push("type", `expected ${types.join(" or ")}, got ${typeName(v)}`);
				return;
			}
		}
		if (Array.isArray(node.enum) && !(0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.enumCheck)(node.enum)(v))
			push("enum", `expected one of ${listValues(node.enum)}`);
		if (Object.hasOwn(node, "const") && !(0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.constCheck)(node.const)(v))
			push("const", `expected ${JSON.stringify(node.const)}`);
		if (typeof v === "string" && node.type !== "bytes") this.walkString(node, v, push);
		if (v instanceof Uint8Array && node.type === "bytes") this.walkBytes(node, v, push);
		if (typeof v === "number") this.walkNumber(node, v, push);
		if ((0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.isObject)(v)) this.walkObject(node, b, v, path, out, push);
		if (Array.isArray(v)) this.walkArray(node, b, v, path, out, push);
		if (Array.isArray(node.allOf)) for (const s of node.allOf) this.walk(s, b, v, path, out);
		if (Array.isArray(node.anyOf)) this.walkUnion(node.anyOf, b, v, path, out, false);
		if (Array.isArray(node.oneOf)) this.walkUnion(node.oneOf, b, v, path, out, true);
		if (node.not !== undefined && this.ok(node.not, b, v)) push("not", "must not match the schema in not");
		if (node.if !== undefined) {
			const branch = this.ok(node.if, b, v) ? node.then : node.else;
			if (branch !== undefined) this.walk(branch, b, v, path, out);
		}
		if (typeof node.$ref === "string") this.walkRef(node.$ref, b, v, path, out);
		if (out.length === start && (node.unevaluatedProperties !== undefined || node.unevaluatedItems !== undefined)) {
			this.walkUnevaluated(node, b, v, path, out);
		}
		if (out.length === start && typeof node.$dynamicRef === "string") {
			push("$dynamicRef", "does not match the dynamically referenced schema");
		}
	}

	        walkRef(ref        , base        , v         , path        , out                   )       {
		const target = this.c.r.resolve(ref, base);
		if (target.node === false && this.c.r.isUnresolved(ref, base)) {
			out.push({ path, keyword: "$ref", message: `unresolved reference ${ref}` });
			return;
		}
		if (!(0,_resolver_ts__WEBPACK_IMPORTED_MODULE_2__.isSchemaObject)(target.node) || typeof v !== "object" || v === null) {
			this.walk(target.node, target.base, v, path, out);
			return;
		}
		this.memo ??= new Map();
		let values = this.memo.get(target.node);
		const known = values?.get(v);
		if (known !== undefined && known.path === path) {
			out.push(...known.errors);
			return;
		}
		const errors                    = [];
		this.walk(target.node, target.base, v, path, errors);
		if (values === undefined) {
			values = new Map();
			this.memo.set(target.node, values);
		}
		values.set(v, { path, errors });
		out.push(...errors);
	}

	        walkString(node              , v        , push      )       {
		const n = (0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.codePoints)(v);
		if (typeof node.minLength === "number" && n < node.minLength) {
			push("minLength", `expected at least ${node.minLength} characters`);
		}
		if (typeof node.maxLength === "number" && n > node.maxLength) {
			push("maxLength", `expected at most ${node.maxLength} characters`);
		}
		if (typeof node.pattern === "string" && !(0,_shared_ts__WEBPACK_IMPORTED_MODULE_3__.sharedRegex)(node.pattern).test(v)) {
			push("pattern", `expected to match ${node.pattern}`);
		}
		const formats = this.c.formats;
		const format =
			formats !== undefined && typeof node.format === "string" && Object.hasOwn(formats, node.format)
				? formats[node.format]
				: undefined;
		if (format !== undefined && !format(v)) push("format", `expected format ${node.format}`);
	}

	        walkBytes(node              , v            , push      )       {
		if (typeof node.minLength === "number" && v.length < node.minLength) {
			push("minLength", `expected at least ${node.minLength} bytes`);
		}
		if (typeof node.maxLength === "number" && v.length > node.maxLength) {
			push("maxLength", `expected at most ${node.maxLength} bytes`);
		}
	}

	        walkNumber(node              , v        , push      )       {
		if (typeof node.minimum === "number" && v < node.minimum) push("minimum", `expected >= ${node.minimum}`);
		if (typeof node.maximum === "number" && v > node.maximum) push("maximum", `expected <= ${node.maximum}`);
		if (typeof node.exclusiveMinimum === "number" && v <= node.exclusiveMinimum) {
			push("exclusiveMinimum", `expected > ${node.exclusiveMinimum}`);
		}
		if (typeof node.exclusiveMaximum === "number" && v >= node.exclusiveMaximum) {
			push("exclusiveMaximum", `expected < ${node.exclusiveMaximum}`);
		}
		if (typeof node.multipleOf === "number" && !(0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.isMultiple)(v, node.multipleOf)) {
			push("multipleOf", `expected a multiple of ${node.multipleOf}`);
		}
	}

	        walkObject(
		node              ,
		b        ,
		v                         ,
		path        ,
		out                   ,
		push      ,
	)       {
		const properties = (0,_resolver_ts__WEBPACK_IMPORTED_MODULE_2__.isSchemaObject)(node.properties) ? node.properties : {};
		const required = Array.isArray(node.required) ? node.required : [];
		for (const k of required) {
			if (typeof k === "string" && (!Object.hasOwn(v, k) || v[k] === undefined))
				push("required", "missing", pointer(path, k));
		}
		for (const k of Object.keys(properties)) {
			if (Object.hasOwn(v, k) && v[k] !== undefined) this.walk(properties[k], b, v[k], pointer(path, k), out);
		}
		const patterns = (0,_resolver_ts__WEBPACK_IMPORTED_MODULE_2__.isSchemaObject)(node.patternProperties) ? Object.entries(node.patternProperties) : [];
		for (const k of Object.keys(v)) {
			let matched = Object.hasOwn(properties, k);
			for (const [p, s] of patterns) {
				if ((0,_shared_ts__WEBPACK_IMPORTED_MODULE_3__.sharedRegex)(p).test(k)) {
					matched = true;
					this.walk(s, b, v[k], pointer(path, k), out);
				}
			}
			if (!matched && node.additionalProperties !== undefined) {
				if (node.additionalProperties === false) push("additionalProperties", "unknown field", pointer(path, k));
				else this.walk(node.additionalProperties, b, v[k], pointer(path, k), out);
			}
			if (node.propertyNames !== undefined && !this.ok(node.propertyNames, b, k)) {
				push("propertyNames", "invalid property name", pointer(path, k));
			}
		}
		const count = Object.keys(v).length;
		if (typeof node.minProperties === "number" && count < node.minProperties) {
			push("minProperties", `expected at least ${node.minProperties} properties`);
		}
		if (typeof node.maxProperties === "number" && count > node.maxProperties) {
			push("maxProperties", `expected at most ${node.maxProperties} properties`);
		}
		const dependencies = {
			...(this.c.draft === "7" || !(0,_resolver_ts__WEBPACK_IMPORTED_MODULE_2__.isSchemaObject)(node.dependentRequired) ? {} : node.dependentRequired),
			...((0,_resolver_ts__WEBPACK_IMPORTED_MODULE_2__.isSchemaObject)(node.dependencies) ? node.dependencies : {}),
		};
		for (const [k, d] of Object.entries(dependencies)) {
			if (!Object.hasOwn(v, k)) continue;
			if (!Array.isArray(d)) this.walk(d, b, v, path, out);
			else
				for (const r of d)
					if (!Object.hasOwn(v, r)) push("dependentRequired", `required when ${k} is present`, pointer(path, r));
		}
		if (this.c.draft !== "7" && (0,_resolver_ts__WEBPACK_IMPORTED_MODULE_2__.isSchemaObject)(node.dependentSchemas)) {
			for (const [k, s] of Object.entries(node.dependentSchemas))
				if (Object.hasOwn(v, k)) this.walk(s, b, v, path, out);
		}
	}

	        walkArray(
		node              ,
		b        ,
		v           ,
		path        ,
		out                   ,
		push      ,
	)       {
		let prefix                     = [];
		let rest         ;
		if (Array.isArray(node.items)) {
			prefix = node.items;
			rest = node.additionalItems;
		} else {
			if (this.c.draft !== "7" && Array.isArray(node.prefixItems)) prefix = node.prefixItems;
			rest = node.items;
		}
		for (let i = 0; i < v.length; i++) {
			const s = i < prefix.length ? prefix[i] : rest;
			if (s !== undefined) this.walk(s, b, v[i], `${path}/${i}`, out);
		}
		if (typeof node.minItems === "number" && v.length < node.minItems) {
			push("minItems", `expected at least ${node.minItems} items`);
		}
		if (typeof node.maxItems === "number" && v.length > node.maxItems) {
			push("maxItems", `expected at most ${node.maxItems} items`);
		}
		if (node.uniqueItems === true && !(0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.uniqueCheck)(v)) push("uniqueItems", "expected unique items");
		if (node.contains !== undefined) {
			const draft7 = this.c.draft === "7";
			const min = draft7 || typeof node.minContains !== "number" ? 1 : node.minContains;
			const max = draft7 || typeof node.maxContains !== "number" ? Number.POSITIVE_INFINITY : node.maxContains;
			if (!(0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.containsCheck)(this.c.check(node.contains, b), min, max)(v)) {
				push("contains", containsMessage(min, max));
			}
		}
	}

	        walkUnion(
		branches                    ,
		b        ,
		v         ,
		path        ,
		out                   ,
		exactlyOne         ,
	)       {
		const matches = branches.filter((s) => this.ok(s, b, v)).length;
		if (matches === 1 || (!exactlyOne && matches > 0)) return;
		const keyword = exactlyOne ? "oneOf" : "anyOf";
		if (matches > 1) {
			out.push({ path, keyword, message: "matches more than one alternative" });
			return;
		}
		// A tagged union reports the branch its tag selects; otherwise the branch whose first error is deepest.
		const tag = this.tagOf(branches, b);
		if (tag !== undefined && (0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.isObject)(v)) {
			const i = tag.values.indexOf(v[tag.key]);
			if (i < 0)
				out.push({
					path: pointer(path, tag.key),
					keyword,
					message: `expected one of ${listValues(tag.values)}`,
				});
			else this.walk(branches[i], b, v, path, out);
			return;
		}
		// An object is blamed on the branch that declares most of its properties (the one it was most likely meant for),
		// then on the branch whose first error is deepest.
		let best                                                             ;
		for (const s of branches) {
			const errors                    = [];
			this.walk(s, b, v, path, errors);
			if (errors.length === 0) continue;
			const declared = (0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.isObject)(v) ? this.declaredKeys(s, b, v) : 0;
			const better =
				best === undefined ||
				declared > best.declared ||
				(declared === best.declared && errors[0].path.length > best.errors[0].path.length);
			if (better) best = { errors, declared };
		}
		out.push(...(best?.errors ?? [{ path, keyword, message: "matches no alternative" }]));
	}

	/** How many of the keys of `v` the branch `s` (through a `$ref`) declares in `properties`. */
	        declaredKeys(s         , b        , v                         )         {
		const node = (0,_resolver_ts__WEBPACK_IMPORTED_MODULE_2__.isSchemaObject)(s) && typeof s.$ref === "string" ? this.c.r.resolve(s.$ref, b).node : s;
		const properties = (0,_resolver_ts__WEBPACK_IMPORTED_MODULE_2__.isSchemaObject)(node) && (0,_resolver_ts__WEBPACK_IMPORTED_MODULE_2__.isSchemaObject)(node.properties) ? node.properties : {};
		return Object.keys(v).filter((k) => Object.hasOwn(properties, k)).length;
	}

	        tagOf(branches                    , b        )                                                 {
		const resolved = branches.map((s) =>
			(0,_resolver_ts__WEBPACK_IMPORTED_MODULE_2__.isSchemaObject)(s) && typeof s.$ref === "string" ? this.c.r.resolve(s.$ref, b).node : s,
		);
		const first = resolved[0];
		if (!(0,_resolver_ts__WEBPACK_IMPORTED_MODULE_2__.isSchemaObject)(first) || !(0,_resolver_ts__WEBPACK_IMPORTED_MODULE_2__.isSchemaObject)(first.properties)) return undefined;
		for (const key of Object.keys(first.properties)) {
			const values = resolved.map((s) => {
				const tag = (0,_resolver_ts__WEBPACK_IMPORTED_MODULE_2__.isSchemaObject)(s) && (0,_resolver_ts__WEBPACK_IMPORTED_MODULE_2__.isSchemaObject)(s.properties) ? s.properties[key] : undefined;
				return (0,_resolver_ts__WEBPACK_IMPORTED_MODULE_2__.isSchemaObject)(tag) ? tag.const : undefined;
			});
			const primitive = values.every((x) => x !== undefined && (typeof x !== "object" || x === null));
			if (primitive && new Set(values).size === values.length) return { key, values };
		}
		return undefined;
	}

	        walkUnevaluated(node              , b        , v         , path        , out                   )       {
		const { unevaluatedProperties, unevaluatedItems, ...rest } = node;
		const a = (0,_annotations_ts__WEBPACK_IMPORTED_MODULE_0__.newAnnotations)();
		this.c.annotating(rest, b)(v, a);
		if ((0,_checks_ts__WEBPACK_IMPORTED_MODULE_1__.isObject)(v) && unevaluatedProperties !== undefined && !a.allProps) {
			for (const k of Object.keys(v)) {
				if (a.props?.has(k)) continue;
				if (unevaluatedProperties === false) {
					out.push({ path: pointer(path, k), keyword: "unevaluatedProperties", message: "unknown field" });
				} else this.walk(unevaluatedProperties, b, v[k], pointer(path, k), out);
			}
		}
		if (Array.isArray(v) && unevaluatedItems !== undefined && !a.allItems) {
			for (let i = a.items; i < v.length; i++) {
				if (a.indexes?.has(i)) continue;
				if (unevaluatedItems === false) {
					out.push({ path: `${path}/${i}`, keyword: "unevaluatedItems", message: "unexpected item" });
				} else this.walk(unevaluatedItems, b, v[i], `${path}/${i}`, out);
			}
		}
	}
}

                                                                    

// Enum errors name the allowed values; a long list (an enum of thousands) is cut, so the message stays readable.
const LISTED_VALUES = 20;

/** `values` as JSON, at most LISTED_VALUES of them, then how many more there are. */
function listValues(values                    )         {
	if (values.length <= LISTED_VALUES) return JSON.stringify(values);
	const listed = JSON.stringify(values.slice(0, LISTED_VALUES));
	return `${listed.slice(0, -1)}, ... and ${values.length - LISTED_VALUES} more]`;
}

function containsMessage(min        , max        )         {
	if (max === Number.POSITIVE_INFINITY) return `expected at least ${min} matching ${min === 1 ? "item" : "items"}`;
	if (min === 0) return `expected at most ${max} matching ${max === 1 ? "item" : "items"}`;
	return `expected between ${min} and ${max} matching items`;
}

function pointer(path        , key                 )         {
	return `${path}/${String(key).replace(/~/g, "~0").replace(/\//g, "~1")}`;
}

function typeName(v         )         {
	if (v === null) return "null";
	if (Array.isArray(v)) return "array";
	if (v instanceof Uint8Array) return "bytes";
	if (typeof v === "number" && Number.isInteger(v)) return "integer";
	return typeof v;
}


/***/ }),
/* 17 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   formats: () => (/* binding */ formats)
/* harmony export */ });
/* harmony import */ var _typebox_date_ts__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(18);
/* harmony import */ var _typebox_date_time_ts__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(19);
/* harmony import */ var _typebox_duration_ts__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(21);
/* harmony import */ var _typebox_email_ts__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(22);
/* harmony import */ var _typebox_hostname_ts__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__(23);
/* harmony import */ var _typebox_idn_email_ts__WEBPACK_IMPORTED_MODULE_5__ = __webpack_require__(31);
/* harmony import */ var _typebox_idn_hostname_ts__WEBPACK_IMPORTED_MODULE_6__ = __webpack_require__(32);
/* harmony import */ var _typebox_ipv4_ts__WEBPACK_IMPORTED_MODULE_7__ = __webpack_require__(34);
/* harmony import */ var _typebox_ipv6_ts__WEBPACK_IMPORTED_MODULE_8__ = __webpack_require__(35);
/* harmony import */ var _typebox_iri_ts__WEBPACK_IMPORTED_MODULE_9__ = __webpack_require__(36);
/* harmony import */ var _typebox_iri_reference_ts__WEBPACK_IMPORTED_MODULE_10__ = __webpack_require__(37);
/* harmony import */ var _typebox_json_pointer_ts__WEBPACK_IMPORTED_MODULE_11__ = __webpack_require__(38);
/* harmony import */ var _typebox_json_pointer_uri_fragment_ts__WEBPACK_IMPORTED_MODULE_12__ = __webpack_require__(39);
/* harmony import */ var _typebox_regex_ts__WEBPACK_IMPORTED_MODULE_13__ = __webpack_require__(40);
/* harmony import */ var _typebox_relative_json_pointer_ts__WEBPACK_IMPORTED_MODULE_14__ = __webpack_require__(41);
/* harmony import */ var _typebox_time_ts__WEBPACK_IMPORTED_MODULE_15__ = __webpack_require__(20);
/* harmony import */ var _typebox_uri_ts__WEBPACK_IMPORTED_MODULE_16__ = __webpack_require__(42);
/* harmony import */ var _typebox_uri_reference_ts__WEBPACK_IMPORTED_MODULE_17__ = __webpack_require__(43);
/* harmony import */ var _typebox_uri_template_ts__WEBPACK_IMPORTED_MODULE_18__ = __webpack_require__(44);
/* harmony import */ var _typebox_url_ts__WEBPACK_IMPORTED_MODULE_19__ = __webpack_require__(45);
/* harmony import */ var _typebox_uuid_ts__WEBPACK_IMPORTED_MODULE_20__ = __webpack_require__(46);
// The format checks of JSON Schema 2020-12, for compile()'s `formats` option. An optional entry point: schemas
// compiled without it treat `format` as an annotation and do not bundle these checks.

                                               






















/**
 * Every format JSON Schema 2020-12 defines, by name, plus `url` and `json-pointer-uri-fragment`, which TypeBox and
 * ajv-formats also check. The checks are adapted from TypeBox 1.3.27 (MIT).
 */
const formats                                        = Object.freeze({
	date: _typebox_date_ts__WEBPACK_IMPORTED_MODULE_0__.IsDate,
	"date-time": _typebox_date_time_ts__WEBPACK_IMPORTED_MODULE_1__.IsDateTime,
	duration: _typebox_duration_ts__WEBPACK_IMPORTED_MODULE_2__.IsDuration,
	email: _typebox_email_ts__WEBPACK_IMPORTED_MODULE_3__.IsEmail,
	hostname: _typebox_hostname_ts__WEBPACK_IMPORTED_MODULE_4__.IsHostname,
	"idn-email": _typebox_idn_email_ts__WEBPACK_IMPORTED_MODULE_5__.IsIdnEmail,
	"idn-hostname": _typebox_idn_hostname_ts__WEBPACK_IMPORTED_MODULE_6__.IsIdnHostname,
	ipv4: _typebox_ipv4_ts__WEBPACK_IMPORTED_MODULE_7__.IsIPv4,
	ipv6: _typebox_ipv6_ts__WEBPACK_IMPORTED_MODULE_8__.IsIPv6,
	iri: _typebox_iri_ts__WEBPACK_IMPORTED_MODULE_9__.IsIri,
	"iri-reference": _typebox_iri_reference_ts__WEBPACK_IMPORTED_MODULE_10__.IsIriReference,
	"json-pointer": _typebox_json_pointer_ts__WEBPACK_IMPORTED_MODULE_11__.IsJsonPointer,
	"json-pointer-uri-fragment": _typebox_json_pointer_uri_fragment_ts__WEBPACK_IMPORTED_MODULE_12__.IsJsonPointerUriFragment,
	regex: _typebox_regex_ts__WEBPACK_IMPORTED_MODULE_13__.IsRegex,
	"relative-json-pointer": _typebox_relative_json_pointer_ts__WEBPACK_IMPORTED_MODULE_14__.IsRelativeJsonPointer,
	time: _typebox_time_ts__WEBPACK_IMPORTED_MODULE_15__.IsTime,
	uri: _typebox_uri_ts__WEBPACK_IMPORTED_MODULE_16__.IsUri,
	"uri-reference": _typebox_uri_reference_ts__WEBPACK_IMPORTED_MODULE_17__.IsUriReference,
	"uri-template": _typebox_uri_template_ts__WEBPACK_IMPORTED_MODULE_18__.IsUriTemplate,
	url: _typebox_url_ts__WEBPACK_IMPORTED_MODULE_19__.IsUrl,
	uuid: _typebox_uuid_ts__WEBPACK_IMPORTED_MODULE_20__.IsUuid,
});


/***/ }),
/* 18 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   IsDate: () => (/* binding */ IsDate)
/* harmony export */ });
/*--------------------------------------------------------------------------

TypeBox

The MIT License (MIT)

Copyright (c) 2017-2026 Haydn Paterson

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

---------------------------------------------------------------------------*/

const DAYS = [0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
const DATE = /^(\d\d\d\d)-(\d\d)-(\d\d)$/;

function IsLeapYear(year        )          {
	return year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
}
/**
 * Returns true if the value is a ISO8601 Date component string
 * @description https://datatracker.ietf.org/doc/html/rfc3339
 * @example `2020-12-12`
 */
function IsDate(value        )          {
	const matches                  = DATE.exec(value);
	if (!matches) return false;
	const year         = +matches[1];
	const month         = +matches[2];
	const day         = +matches[3];
	return month >= 1 && month <= 12 && day >= 1 && day <= (month === 2 && IsLeapYear(year) ? 29 : DAYS[month]);
}


/***/ }),
/* 19 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   IsDateTime: () => (/* binding */ IsDateTime)
/* harmony export */ });
/* harmony import */ var _date_ts__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(18);
/* harmony import */ var _time_ts__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(20);
/*--------------------------------------------------------------------------

TypeBox

The MIT License (MIT)

Copyright (c) 2017-2026 Haydn Paterson

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

---------------------------------------------------------------------------*/




/**
 * Returns true if the value is a ISO8601 DateTime string
 * @specification https://datatracker.ietf.org/doc/html/rfc3339
 * @example `2020-12-12T20:20:40+00:00`
 */
function IsDateTime(value        )          {
	const dateTime           = value.split(/T/i);
	return dateTime.length === 2 && (0,_date_ts__WEBPACK_IMPORTED_MODULE_0__.IsDate)(dateTime[0]) && (0,_time_ts__WEBPACK_IMPORTED_MODULE_1__.IsTime)(dateTime[1]);
}


/***/ }),
/* 20 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   IsTime: () => (/* binding */ IsTime)
/* harmony export */ });
/*--------------------------------------------------------------------------

TypeBox

The MIT License (MIT)

Copyright (c) 2017-2026 Haydn Paterson

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

---------------------------------------------------------------------------*/

const TIME = /^(\d\d):(\d\d):(\d\d)(?:\.\d+)?(?:([Zz])|([+-])(\d\d):(\d\d))?$/;

/**
 * Returns true if the value is an ISO time string
 * @specification https://datatracker.ietf.org/doc/html/rfc3339
 */
function IsTime(value        , strictTimeZone          = true)          {
	const matches = TIME.exec(value);
	if (!matches) return false;
	// Require timezone offset or 'Z'/'z' when strictTimeZone is true
	if (strictTimeZone && !matches[4] && !matches[5]) return false;
	const hr = +matches[1];
	const min = +matches[2];
	const sec = +matches[3];
	if (hr > 23 || min > 59 || sec > 60) return false;
	if (matches[5]) {
		const tzH = +matches[6];
		const tzM = +matches[7];
		if (tzH > 23 || tzM > 59) return false;
	}
	if (sec < 60) return true;
	// Leap second handling: must normalize to 23:59:60 UTC (1439 total UTC minutes)
	const tzSign = matches[5] === "-" ? -1 : 1;
	const tzH = +(matches[6] || 0);
	const tzM = +(matches[7] || 0);
	const totalUtcMin = hr * 60 + min - tzSign * (tzH * 60 + tzM);
	return ((totalUtcMin % 1440) + 1440) % 1440 === 1439;
}


/***/ }),
/* 21 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   IsDuration: () => (/* binding */ IsDuration)
/* harmony export */ });
/*--------------------------------------------------------------------------

TypeBox

The MIT License (MIT)

Copyright (c) 2017-2026 Haydn Paterson

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

---------------------------------------------------------------------------*/

const Duration =
	/^P((\d+Y(\d+M(\d+D)?)?|\d+M(\d+D)?|\d+D)(T(\d+H(\d+M(\d+S)?)?|\d+M(\d+S)?|\d+S))?|T(\d+H(\d+M(\d+S)?)?|\d+M(\d+S)?|\d+S)|\d+W)$/;

/**
 * Returns true if the value is a valid ISO-8601 duration.
 * @specification https://tools.ietf.org/html/rfc3339
 */
function IsDuration(value        )          {
	return Duration.test(value);
}


/***/ }),
/* 22 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   IsEmail: () => (/* binding */ IsEmail)
/* harmony export */ });
/*--------------------------------------------------------------------------

TypeBox

The MIT License (MIT)

Copyright (c) 2017-2026 Haydn Paterson

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

---------------------------------------------------------------------------*/

const Email =
	/^(?:[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*|"(?:[^"\\]|\\[\x20-\x7e])*")@(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*|\[(?:IPv6:[a-f0-9:]+|(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])(?:\.(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3})\])$/i;

/**
 * Returns true if the value is an Email
 * @specification https://datatracker.ietf.org/doc/html/rfc5322
 */
function IsEmail(value        )          {
	return Email.test(value);
}


/***/ }),
/* 23 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   IsHostname: () => (/* binding */ IsHostname)
/* harmony export */ });
/* harmony import */ var _idna_index_ts__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(24);
/*--------------------------------------------------------------------------

TypeBox

The MIT License (MIT)

Copyright (c) 2017-2026 Haydn Paterson

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

---------------------------------------------------------------------------*/



/**
 * Returns true if the value is a valid hostname.
 * @specification https://tools.ietf.org/html/rfc1123
 * @specification https://tools.ietf.org/html/rfc5891
 * @specification https://tools.ietf.org/html/rfc5892
 */
function IsHostname(value        )          {
	return _idna_index_ts__WEBPACK_IMPORTED_MODULE_0__.IsHostname(value);
}


/***/ }),
/* 24 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   IsHostname: () => (/* binding */ IsHostname)
/* harmony export */ });
/* harmony import */ var _label_ascii_ts__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(25);
/* harmony import */ var _label_puny_ts__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(27);
/*--------------------------------------------------------------------------

TypeBox

The MIT License (MIT)

Copyright (c) 2017-2026 Haydn Paterson

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

---------------------------------------------------------------------------*/




function IsValidLabelLength(value        )          {
	return value.length > 0 && value.length <= 63;
}
function IsLabel(value        )          {
	return IsValidLabelLength(value) && (_label_puny_ts__WEBPACK_IMPORTED_MODULE_1__.IsPunyLabel(value) || _label_ascii_ts__WEBPACK_IMPORTED_MODULE_0__.IsAsciiLabel(value));
}
function IsHostname(value        )          {
	if (value.length === 0 || value.length > 253) return false;
	if (value.charCodeAt(value.length - 1) === 46) return false;
	return value.split(".").every((label) => IsLabel(label));
}


/***/ }),
/* 25 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   IsAsciiLabel: () => (/* binding */ IsAsciiLabel)
/* harmony export */ });
/* harmony import */ var _pattern_pattern_ts__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(26);
/*--------------------------------------------------------------------------

TypeBox

The MIT License (MIT)

Copyright (c) 2017-2026 Haydn Paterson

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

---------------------------------------------------------------------------*/



// -------------------------------------------------------------------
// Validates an ASCII LDH label per RFC 5891 §4.2.3.1
// -------------------------------------------------------------------
function IsAsciiLabel(value        )          {
	return (
		_pattern_pattern_ts__WEBPACK_IMPORTED_MODULE_0__.RE_RULE_HYPHEN_PLACEMENT.test(value) &&
		_pattern_pattern_ts__WEBPACK_IMPORTED_MODULE_0__.RE_RULE_NOT_RESERVED_ACE.test(value) &&
		_pattern_pattern_ts__WEBPACK_IMPORTED_MODULE_0__.RE_ASCII_LDH.test(value)
	);
}


/***/ }),
/* 26 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   RE_ARABIC_INDIC_DIGIT: () => (/* binding */ RE_ARABIC_INDIC_DIGIT),
/* harmony export */   RE_ASCII: () => (/* binding */ RE_ASCII),
/* harmony export */   RE_ASCII_DIGIT: () => (/* binding */ RE_ASCII_DIGIT),
/* harmony export */   RE_ASCII_LDH: () => (/* binding */ RE_ASCII_LDH),
/* harmony export */   RE_COMBINING_MARK: () => (/* binding */ RE_COMBINING_MARK),
/* harmony export */   RE_COMMON_SEPARATOR: () => (/* binding */ RE_COMMON_SEPARATOR),
/* harmony export */   RE_CONTEXTO_EXCEPTIONS: () => (/* binding */ RE_CONTEXTO_EXCEPTIONS),
/* harmony export */   RE_EUROPEAN_NUMBER: () => (/* binding */ RE_EUROPEAN_NUMBER),
/* harmony export */   RE_EUROPEAN_SEPARATOR: () => (/* binding */ RE_EUROPEAN_SEPARATOR),
/* harmony export */   RE_EXT_ARABIC_INDIC_DIGIT: () => (/* binding */ RE_EXT_ARABIC_INDIC_DIGIT),
/* harmony export */   RE_HIGH_SURROGATE: () => (/* binding */ RE_HIGH_SURROGATE),
/* harmony export */   RE_LETTER: () => (/* binding */ RE_LETTER),
/* harmony export */   RE_MARK_ENCLOSING: () => (/* binding */ RE_MARK_ENCLOSING),
/* harmony export */   RE_MARK_NONSPACING: () => (/* binding */ RE_MARK_NONSPACING),
/* harmony export */   RE_MARK_SPACING_COMBINING: () => (/* binding */ RE_MARK_SPACING_COMBINING),
/* harmony export */   RE_NON_ASCII: () => (/* binding */ RE_NON_ASCII),
/* harmony export */   RE_NUMBER_DECIMAL: () => (/* binding */ RE_NUMBER_DECIMAL),
/* harmony export */   RE_PERMITTED_CATEGORY: () => (/* binding */ RE_PERMITTED_CATEGORY),
/* harmony export */   RE_PVALID_EXCEPTIONS: () => (/* binding */ RE_PVALID_EXCEPTIONS),
/* harmony export */   RE_REGIONAL_INDICATOR: () => (/* binding */ RE_REGIONAL_INDICATOR),
/* harmony export */   RE_RFC5892_DISALLOWED: () => (/* binding */ RE_RFC5892_DISALLOWED),
/* harmony export */   RE_RULE_HYPHEN_PLACEMENT: () => (/* binding */ RE_RULE_HYPHEN_PLACEMENT),
/* harmony export */   RE_RULE_NOT_RESERVED_ACE: () => (/* binding */ RE_RULE_NOT_RESERVED_ACE),
/* harmony export */   RE_SCRIPT_ARABIC_LETTER: () => (/* binding */ RE_SCRIPT_ARABIC_LETTER),
/* harmony export */   RE_SCRIPT_GREEK: () => (/* binding */ RE_SCRIPT_GREEK),
/* harmony export */   RE_SCRIPT_HEBREW: () => (/* binding */ RE_SCRIPT_HEBREW),
/* harmony export */   RE_SCRIPT_JAPANESE: () => (/* binding */ RE_SCRIPT_JAPANESE),
/* harmony export */   RE_VARIATION_SELECTOR: () => (/* binding */ RE_VARIATION_SELECTOR),
/* harmony export */   RE_VIRAMA: () => (/* binding */ RE_VIRAMA),
/* harmony export */   RE_ZERO_WIDTH_JOINER: () => (/* binding */ RE_ZERO_WIDTH_JOINER)
/* harmony export */ });
/*--------------------------------------------------------------------------

TypeBox

The MIT License (MIT)

Copyright (c) 2017-2026 Haydn Paterson

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

---------------------------------------------------------------------------*/

// deno-fmt-ignore-file

// -------------------------------------------------------------------
// Validation Rules
// -------------------------------------------------------------------
const RE_RULE_HYPHEN_PLACEMENT = /^(?!-).*(?<!-)$/;
const RE_RULE_NOT_RESERVED_ACE = /^(?!..--)/;
const RE_ASCII_LDH = /^[a-zA-Z0-9-]*$/;

// -------------------------------------------------------------------
// ASCII & Encoding
// -------------------------------------------------------------------
const RE_ASCII = /^\p{ASCII}*$/u;
const RE_NON_ASCII = /[^\p{ASCII}]/u;

// -------------------------------------------------------------------
// Structural & Unicode Controls
// -------------------------------------------------------------------
const RE_HIGH_SURROGATE = /[\u{d800}-\u{dbff}]/u;
const RE_REGIONAL_INDICATOR = /[\u{1f1e6}-\u{1f1ff}]/u;
const RE_VARIATION_SELECTOR = /[\u{fe00}-\u{fe0f}]/u;
const RE_ZERO_WIDTH_JOINER = /\u{200d}/u;

// -------------------------------------------------------------------
// Numbers & Digits
// -------------------------------------------------------------------
const RE_ASCII_DIGIT = /[0-9]/;
const RE_ARABIC_INDIC_DIGIT = /[\u{0660}-\u{0669}]/u;
const RE_EXT_ARABIC_INDIC_DIGIT = /[\u{06f0}-\u{06f9}]/u;

// -------------------------------------------------------------------
// Separators
// -------------------------------------------------------------------
const RE_COMMON_SEPARATOR = /[\u{002e}\u{002c}\u{003a}\u{002f}]/u;
const RE_EUROPEAN_SEPARATOR = /[\u{002d}\u{002b}]/u;

// -------------------------------------------------------------------
// General Categories
// -------------------------------------------------------------------
const RE_MARK_NONSPACING = /\p{Mn}/u;
const RE_MARK_SPACING_COMBINING = /\p{Mc}/u;
const RE_MARK_ENCLOSING = /\p{Me}/u;
const RE_COMBINING_MARK = /[\p{Mn}\p{Mc}\p{Me}]/u;
const RE_LETTER = /\p{L}/u;
const RE_NUMBER_DECIMAL = /\p{Nd}/u;

// -------------------------------------------------------------------
// Scripts
// -------------------------------------------------------------------
const RE_SCRIPT_GREEK = /\p{Script=Greek}/u;
const RE_SCRIPT_HEBREW = /\p{Script=Hebrew}/u;
const RE_SCRIPT_JAPANESE = /[\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Han}]/u;
const RE_SCRIPT_ARABIC_LETTER = /[\p{Script=Arabic}\p{Script=Syriac}\p{Script=Thaana}\p{Script=Mandaic}]/u;

// -------------------------------------------------------------------
// RFC Specifications & Context Exceptions
// -------------------------------------------------------------------
const RE_VIRAMA =
	/[\u{094d}\u{09cd}\u{0a4d}\u{0acd}\u{0b4d}\u{0bcd}\u{0c4d}\u{0ccd}\u{0d3b}\u{0d3c}\u{0d4d}\u{0dca}\u{1b44}\u{1baa}\u{1bab}\u{a9c0}\u{11046}\u{1107f}\u{110b9}\u{11133}\u{11134}\u{111c0}\u{11235}\u{1134d}\u{11442}\u{114c2}\u{115bf}\u{1163f}\u{116b6}\u{11c3f}\u{11d44}\u{11d45}]/u;
const RE_RFC5892_DISALLOWED =
	/[\u{0640}\u{07fa}\u{302e}\u{302f}\u{3031}\u{3032}\u{3033}\u{3034}\u{3035}\u{303b}]/u;
// biome-ignore lint/suspicious/noMisleadingCharacterClass: a set of single code points (u flag), including ZWJ and ZWNJ, by design.
const RE_CONTEXTO_EXCEPTIONS = /[\u{00b7}\u{0375}\u{05f3}\u{05f4}\u{200c}\u{200d}\u{30fb}]/u;
const RE_PVALID_EXCEPTIONS = /[\u{00df}\u{03c2}\u{06fd}\u{06fe}\u{0f0b}\u{3007}]/u;

// -------------------------------------------------------------------
// Composite Patterns
// -------------------------------------------------------------------
const RE_EUROPEAN_NUMBER = new RegExp(
	[RE_ASCII_DIGIT, RE_EXT_ARABIC_INDIC_DIGIT].map((regexp) => regexp.source).join("|"),
	"u",
);

const RE_PERMITTED_CATEGORY = new RegExp(
	[
		RE_LETTER,
		RE_EUROPEAN_SEPARATOR,
		RE_COMMON_SEPARATOR,
		RE_NUMBER_DECIMAL,
		RE_MARK_NONSPACING,
		RE_MARK_SPACING_COMBINING,
		RE_CONTEXTO_EXCEPTIONS,
		RE_PVALID_EXCEPTIONS,
	]
		.map((regexp) => regexp.source)
		.join("|"),
	"u",
);


/***/ }),
/* 27 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   IsPunyLabel: () => (/* binding */ IsPunyLabel)
/* harmony export */ });
/* harmony import */ var _format_puny_ts__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(28);
/* harmony import */ var _pattern_pattern_ts__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(26);
/* harmony import */ var _unicode_ts__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(29);
/*--------------------------------------------------------------------------

TypeBox

The MIT License (MIT)

Copyright (c) 2017-2026 Haydn Paterson

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

---------------------------------------------------------------------------*/





// ------------------------------------------------------------------
// IsPunyLabel
// ------------------------------------------------------------------
function IsPunyLabel(value        )          {
	if (!_format_puny_ts__WEBPACK_IMPORTED_MODULE_0__.IsAcePrefixed(value)) return false;
	try {
		const body = value.slice(4).toLowerCase();
		// A payload consisting of only a hyphen, or starting with the delimiter
		// (like "-9uc"), has 0 basic ASCII characters preceding it and is malformed.
		if (body.lastIndexOf("-") === 0) return false;
		const decoded = _format_puny_ts__WEBPACK_IMPORTED_MODULE_0__.Decode(body);
		// RFC 5890 §2.3.2.1 - a U-label must contain at least one non-ASCII
		// character. Code-unit level check: any non-ASCII codepoint has at least
		// one UTF-16 unit >= 0x80 (surrogate halves included), so no need to
		// decode codepoints or allocate a character array to answer this.
		if (!_pattern_pattern_ts__WEBPACK_IMPORTED_MODULE_1__.RE_NON_ASCII.test(decoded)) return false;
		return _unicode_ts__WEBPACK_IMPORTED_MODULE_2__.IsUnicodeLabel(decoded);
	} catch {
		return false;
	}
}


/***/ }),
/* 28 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   Decode: () => (/* binding */ Decode),
/* harmony export */   Encode: () => (/* binding */ Encode),
/* harmony export */   IsAcePrefixed: () => (/* binding */ IsAcePrefixed)
/* harmony export */ });
/* harmony import */ var _pattern_pattern_ts__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(26);
/*--------------------------------------------------------------------------

TypeBox

The MIT License (MIT)

Copyright (c) 2017-2026 Haydn Paterson

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

---------------------------------------------------------------------------*/



// ------------------------------------------------------------------
// PunyCode (RFC 3492)
// ------------------------------------------------------------------
const PUNYCODE_BASE = 36;
const PUNYCODE_TMIN = 1;
const PUNYCODE_TMAX = 26;
const PUNYCODE_SKEW = 38;
const PUNYCODE_DAMP = 700;
const PUNYCODE_INITIAL_BIAS = 72;
const PUNYCODE_INITIAL_N = 128;

// ------------------------------------------------------------------
// ThrowDontCare
// ------------------------------------------------------------------
function ThrowDontCare()        {
	throw null;
}
// ------------------------------------------------------------------
// IsAcePrefixed
// ------------------------------------------------------------------
function IsAcePrefixed(value        )          {
	return value.toLowerCase().startsWith("xn--");
}
// ------------------------------------------------------------------
// Adapt
// ------------------------------------------------------------------
function Adapt(delta        , numPoints        , firstTime         )         {
	delta = firstTime ? Math.floor(delta / PUNYCODE_DAMP) : delta >> 1;
	delta += Math.floor(delta / numPoints);
	let k = 0;
	while (delta > ((PUNYCODE_BASE - PUNYCODE_TMIN) * PUNYCODE_TMAX) >> 1) {
		delta = Math.floor(delta / (PUNYCODE_BASE - PUNYCODE_TMIN));
		k += PUNYCODE_BASE;
	}
	return k + Math.floor(((PUNYCODE_BASE - PUNYCODE_TMIN + 1) * delta) / (delta + PUNYCODE_SKEW));
}
// ------------------------------------------------------------------
// Decode
// ------------------------------------------------------------------
function Decode(value        )         {
	const output           = [];
	let n = PUNYCODE_INITIAL_N;
	let i = 0;
	let bias = PUNYCODE_INITIAL_BIAS;
	const delimIdx = value.lastIndexOf("-");
	if (delimIdx > 0) {
		for (let j = 0; j < delimIdx; j++) {
			const cp = value.charCodeAt(j);
			if (cp >= 128) ThrowDontCare(); // throw new Error('Invalid punycode: non-basic before delimiter')
			output.push(cp);
		}
	}
	let inIdx = delimIdx < 0 ? 0 : delimIdx + 1;
	while (inIdx < value.length) {
		const oldi = i;
		let w = 1;
		let k = PUNYCODE_BASE;
		while (true) {
			if (inIdx >= value.length) ThrowDontCare(); // throw new Error('Invalid punycode: unexpected end of input')
			const ch = value.charCodeAt(inIdx++);
			let digit        ;
			if (ch >= 0x61 && ch <= 0x7a)
				digit = ch - 0x61; // a-z => 0-25
			else if (ch >= 0x30 && ch <= 0x39)
				digit = ch - 0x30 + 26; // 0-9 => 26-35
			// else if (ch >= 0x41 && ch <= 0x5a) digit = ch - 0x41 // A-Z => 0-25 // (no-spec-coverage)
			else ThrowDontCare(); // throw new Error('Invalid punycode: bad digit character')
			i += digit * w;
			const t = k <= bias ? PUNYCODE_TMIN : k >= bias + PUNYCODE_TMAX ? PUNYCODE_TMAX : k - bias;
			if (digit < t) break;
			w *= PUNYCODE_BASE - t;
			k += PUNYCODE_BASE;
		}
		const outLen = output.length + 1;
		bias = Adapt(i - oldi, outLen, oldi === 0);
		n += Math.floor(i / outLen);
		i %= outLen;
		output.splice(i, 0, n);
		i++;
	}
	return String.fromCodePoint(...output);
}
// ------------------------------------------------------------------
// DigitToChar
// ------------------------------------------------------------------
function DigitToChar(digit        )         {
	return digit < 26 ? String.fromCharCode(digit + 0x61) : String.fromCharCode(digit - 26 + 0x30);
}
// ------------------------------------------------------------------
// Encode
//
// Encodes a Unicode string into its Punycode payload (the part that
// follows the 'xn--' ACE prefix). Used to compute the true A-label
// length of a U-label, since the 63-octet label length limit (RFC
// 5891 §4.2.4) applies to the encoded form, not the code point count.
// ------------------------------------------------------------------
const RE_REPLACE_NON_ASCII = new RegExp(_pattern_pattern_ts__WEBPACK_IMPORTED_MODULE_0__.RE_NON_ASCII.source, "gu");
function Encode(input        )         {
	// Extract basic ASCII characters and set initial output array state
	const basic = input.replace(RE_REPLACE_NON_ASCII, "");
	const basicLength = basic.length;
	const result = basicLength > 0 ? [basic, "-"] : [];
	// Extract unicode codepoints
	const codePoints = Array.from(input, (char) => char.codePointAt(0) );
	let n = PUNYCODE_INITIAL_N;
	let delta = 0;
	let bias = PUNYCODE_INITIAL_BIAS;
	let handledCPCount = basicLength;
	while (handledCPCount < codePoints.length) {
		let m = Infinity;
		for (const cp of codePoints) {
			if (cp >= n && cp < m) m = cp;
		}
		delta += (m - n) * (handledCPCount + 1);
		n = m;
		for (const cp of codePoints) {
			if (cp < n) delta++;
			if (cp === n) {
				let q = delta;
				for (let k = PUNYCODE_BASE; ; k += PUNYCODE_BASE) {
					const t = k <= bias ? PUNYCODE_TMIN : k >= bias + PUNYCODE_TMAX ? PUNYCODE_TMAX : k - bias;
					if (q < t) break;
					const digit = t + ((q - t) % (PUNYCODE_BASE - t));
					result.push(DigitToChar(digit));
					q = Math.floor((q - t) / (PUNYCODE_BASE - t));
				}
				result.push(DigitToChar(q));
				bias = Adapt(delta, handledCPCount + 1, handledCPCount === basicLength);
				delta = 0;
				handledCPCount++;
			}
		}
		delta++;
		n++;
	}
	return result.join("");
}


/***/ }),
/* 29 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   IsUnicodeLabel: () => (/* binding */ IsUnicodeLabel)
/* harmony export */ });
/* harmony import */ var _format_bidi_ts__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(30);
/* harmony import */ var _format_puny_ts__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(28);
/* harmony import */ var _pattern_pattern_ts__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(26);
/*--------------------------------------------------------------------------

TypeBox

The MIT License (MIT)

Copyright (c) 2017-2026 Haydn Paterson

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

---------------------------------------------------------------------------*/





// ------------------------------------------------------------------
// ExceedsMaxALabelLength
//
// RFC 5890 §2.3.2.1: an A-label (the ACE-encoded form of a label,
// "xn--" + punycode) must not exceed 63 octets. Only labels
// containing non-ASCII characters need checking - a pure-ASCII label
// has no "xn--" prefix and is used as-is, so its punycode-encoded
// length is irrelevant. The ASCII check is a cheap short-circuit that
// avoids running the punycode encoder on labels that don't need it.
// ------------------------------------------------------------------
function ExceedsMaxALabelLength(value        )          {
	return _pattern_pattern_ts__WEBPACK_IMPORTED_MODULE_2__.RE_NON_ASCII.test(value) && _format_puny_ts__WEBPACK_IMPORTED_MODULE_1__.Encode(value).length + 4 > 63;
}
// ------------------------------------------------------------------
// HasInvalidHyphens
//
// RFC 5891 §4.2.3.2: label must not start or end with a hyphen, and
// must not have hyphens in the 3rd and 4th codepoint positions (the
// ACE prefix reservation, e.g. "xn--"). Operates on `chars` (the
// per-codepoint string array) rather than raw string indices so
// supplementary-plane characters earlier in the label don't throw
// off the codepoint-position count.
// ------------------------------------------------------------------
function HasInvalidHyphens(chars          )          {
	if (chars[0] === "-" || chars[chars.length - 1] === "-") return true;
	return chars.slice(2).join("").startsWith("--");
}
// ------------------------------------------------------------------
// IsUnicodeLabel
// ------------------------------------------------------------------
function IsUnicodeLabel(value        )          {
	// RFC 5890 §2.3.2.1: Ensure Max Length
	if (ExceedsMaxALabelLength(value)) return false;
	// RFC 5893 - Bidi Rule (applies whenever this label itself is RTL)
	if (_format_bidi_ts__WEBPACK_IMPORTED_MODULE_0__.HasRightToLeftCharacters(value) && !_format_bidi_ts__WEBPACK_IMPORTED_MODULE_0__.SatisfiesBidiRule(value)) return false;
	const chars = [...value];
	const codePoints = chars.map((c) => c.codePointAt(0) );
	const length = codePoints.length;
	// RFC 5891 §4.2.3.2: Hyphen rules
	if (HasInvalidHyphens(chars)) return false;
	// RFC 5891 §4.2.3.2 - Must not begin with a combining mark
	if (_pattern_pattern_ts__WEBPACK_IMPORTED_MODULE_2__.RE_COMBINING_MARK.test(chars[0])) return false;
	let hasJapanese = false;
	// let hasArabicIndic = false // (no-spec-coverage)
	// let hasExtendedArabicIndic = false // (no-spec-coverage)
	for (let i = 0; i < length; i++) {
		const codePoint = codePoints[i];
		const char = chars[i];
		// 1. DISALLOWED exceptions
		if (_pattern_pattern_ts__WEBPACK_IMPORTED_MODULE_2__.RE_RFC5892_DISALLOWED.test(char)) return false;
		// 1b. General category (PVALID) enforcement
		if (!_pattern_pattern_ts__WEBPACK_IMPORTED_MODULE_2__.RE_PERMITTED_CATEGORY.test(char)) return false;
		// 2. Collect Flags
		if (_pattern_pattern_ts__WEBPACK_IMPORTED_MODULE_2__.RE_SCRIPT_JAPANESE.test(char)) hasJapanese = true;
		// if (Pattern.RE_ARABIC_INDIC_DIGIT.test(char)) hasArabicIndic = true  // (no-spec-coverage)
		// if (Pattern.RE_EXT_ARABIC_INDIC_DIGIT.test(char)) hasExtendedArabicIndic = true  // (no-spec-coverage)
		// 3. CONTEXTO / CONTEXTJ Neighbor Rules
		const prev = codePoints[i - 1],
			next = codePoints[i + 1];
		switch (codePoint) {
			case 0x00b7:
				if (prev !== 0x006c || next !== 0x006c) return false;
				break; // MIDDLE DOT (Catalan)
			case 0x0375:
				if (!next || !_pattern_pattern_ts__WEBPACK_IMPORTED_MODULE_2__.RE_SCRIPT_GREEK.test(chars[i + 1])) return false;
				break; // Greek KERAIA
			case 0x05f3:
			case 0x05f4:
				if (!prev || !_pattern_pattern_ts__WEBPACK_IMPORTED_MODULE_2__.RE_SCRIPT_HEBREW.test(chars[i - 1])) return false;
				break; // Hebrew GERESH
			case 0x200c: // ZWNJ - RFC 5892 Appendix A.1
				// Note: Full validation requires a Unicode joining type table. We reject
				// only when preceded by ASCII (U+0000-U+007F), which can never satisfy
				// the Virama or cursive-joining rules.
				if (!prev || (prev < 0x0080 && !_pattern_pattern_ts__WEBPACK_IMPORTED_MODULE_2__.RE_VIRAMA.test(chars[i - 1]))) return false;
				break;
			case 0x200d: // ZWJ: RFC 5892 Appendix A.2
				if (!prev || !_pattern_pattern_ts__WEBPACK_IMPORTED_MODULE_2__.RE_VIRAMA.test(chars[i - 1])) return false;
				break;
			case 0x30fb: // Checked at end via hasJapanese
				break; // KATAKANA MIDDLE DOT
		}
	}
	// 4. Global Context Validations (Post-loop)
	// RFC 5892 Appendix A.7 - Katakana Middle Dot requirement
	if (value.includes("\u30fb") && !hasJapanese) return false;
	// RFC 5892 Appendix A.8/A.9 - Mixing Arabic Digits
	// if (hasArabicIndic && hasExtendedArabicIndic) return false // (no-spec-coverage)
	return true;
}


/***/ }),
/* 30 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   HasBidiChars: () => (/* binding */ HasBidiChars),
/* harmony export */   HasRightToLeftCharacters: () => (/* binding */ HasRightToLeftCharacters),
/* harmony export */   SatisfiesBidiRule: () => (/* binding */ SatisfiesBidiRule)
/* harmony export */ });
/* harmony import */ var _pattern_pattern_ts__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(26);
/* harmony import */ var _puny_ts__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(28);
/*--------------------------------------------------------------------------

TypeBox

The MIT License (MIT)

Copyright (c) 2017-2026 Haydn Paterson

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

---------------------------------------------------------------------------*/

// deno-fmt-ignore-file




// ------------------------------------------------------------------
// LOCAL REGEX: Allowed Bidi classes per direction (RFC 5893)
// ------------------------------------------------------------------
const RE_RTL_ALLOWED = /^(?:R|AL|AN|EN|ES|CS|ET|ON|BN|NSM)$/;
const RE_LTR_ALLOWED = /^(?:L|EN|ES|CS|ET|ON|BN|NSM)$/;
const RE_RTL_CLASSES = /^(?:R|AL|AN)$/;

// ------------------------------------------------------------------
// HasBidiChars
//
// Decodes a punycode label to its Unicode form (if it is one) and
// checks whether the result is an RTL label per RFC 5893 §1.4.
// ------------------------------------------------------------------
function HasBidiChars(value        )          {
	if (_puny_ts__WEBPACK_IMPORTED_MODULE_1__.IsAcePrefixed(value)) {
		try {
			return HasRightToLeftCharacters(_puny_ts__WEBPACK_IMPORTED_MODULE_1__.Decode(value.slice(4).toLowerCase()));
		} catch {
			return false;
		}
	}
	return HasRightToLeftCharacters(value);
}
// ------------------------------------------------------------------
// GetBidiClass
// ------------------------------------------------------------------
function GetBidiClass(codePoint        )         {
	const char = String.fromCodePoint(codePoint);
	return _pattern_pattern_ts__WEBPACK_IMPORTED_MODULE_0__.RE_EUROPEAN_NUMBER.test(char)
		? "EN"
		: _pattern_pattern_ts__WEBPACK_IMPORTED_MODULE_0__.RE_ARABIC_INDIC_DIGIT.test(char)
			? "AN"
			: // Pattern.RE_EUROPEAN_SEPARATOR.test(char) ? 'ES' : // (no-spec-coverage)
				// Pattern.RE_COMMON_SEPARATOR.test(char) ? 'CS' : // (no-spec-coverage)
				_pattern_pattern_ts__WEBPACK_IMPORTED_MODULE_0__.RE_MARK_NONSPACING.test(char)
				? "NSM"
				: _pattern_pattern_ts__WEBPACK_IMPORTED_MODULE_0__.RE_SCRIPT_HEBREW.test(char)
					? "R"
					: _pattern_pattern_ts__WEBPACK_IMPORTED_MODULE_0__.RE_SCRIPT_ARABIC_LETTER.test(char)
						? "AL"
						: _pattern_pattern_ts__WEBPACK_IMPORTED_MODULE_0__.RE_LETTER.test(char)
							? "L"
							: "ON";
}
// ------------------------------------------------------------------
// HasRightToLeftCharacters
//
// Per RFC 5893 §1.4: "An RTL label is a label that contains at least
// one character of type R, AL, or AN." Used to determine whether a
// domain name is a "Bidi domain name" (RFC 5893 §1.4), in which case
// the Bidi Rule applies to every label in the domain.
// ------------------------------------------------------------------
function HasRightToLeftCharacters(value        )          {
	for (const ch of value) if (RE_RTL_CLASSES.test(GetBidiClass(ch.codePointAt(0) ))) return true;
	return false;
}
// ------------------------------------------------------------------
// SatisfiesBidiRule
// ------------------------------------------------------------------
function SatisfiesBidiRule(value        )          {
	let isRtl = false;
	let allowed = RE_LTR_ALLOWED;
	let sawEN = false;
	let sawAN = false;
	let isFirst = true;
	// let lastClass: string | null = null // (no-spec-coverage)
	for (const ch of value) {
		const bidiClass = GetBidiClass(ch.codePointAt(0) );
		if (isFirst) {
			// 1. The first character must be a character with Bidi property L, R, or AL.
			if (bidiClass !== "L" && bidiClass !== "R" && bidiClass !== "AL") return false;
			isRtl = bidiClass === "R" || bidiClass === "AL";
			allowed = isRtl ? RE_RTL_ALLOWED : RE_LTR_ALLOWED;
			isFirst = false;
		}
		// 2 / 5. Only characters in the allowed set for this direction may appear.
		if (!allowed.test(bidiClass)) return false;
		if (bidiClass === "EN") sawEN = true;
		else if (bidiClass === "AN") sawAN = true;
		// if (bidiClass !== 'NSM') lastClass = bidiClass // (no-spec-coverage)
	}
	if (isRtl && sawEN && sawAN) return false; // spec coverage path
	// (no-spec-coverage)
	// if (isRtl) {
	//   // 3. The end of the label must be R, AL, EN, or AN (ignoring trailing NSM).
	//   // if (lastClass !== 'R' && lastClass !== 'AL' && lastClass !== 'EN' && lastClass !== 'AN') return false
	//   // 4. If an EN is present, no AN may be present, and vice versa.
	//   if (sawEN && sawAN) return false
	// } else {
	//   // 6. The end of the label must be L or EN (ignoring trailing NSM).
	//   // if (lastClass !== 'L' && lastClass !== 'EN') return false
	// }
	return true;
}


/***/ }),
/* 31 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   IsIdnEmail: () => (/* binding */ IsIdnEmail)
/* harmony export */ });
/*--------------------------------------------------------------------------

TypeBox

The MIT License (MIT)

Copyright (c) 2017-2026 Haydn Paterson

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

---------------------------------------------------------------------------*/

const IdnEmail =
	/^(?:[A-Za-z0-9!#$%&'*+/=?^_`{|}~\u{0080}-\u{10FFFF}-]+(?:\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~\u{0080}-\u{10FFFF}-]+)*|"(?:[^"\\]|\\.)*")@[\p{L}\p{N}](?:[\p{L}\p{N}-]{0,62})(?<!-)(?:\.[\p{L}\p{N}](?:[\p{L}\p{N}-]{0,62})(?<!-))*$/iu;

/**
 * Returns true if the value is an IdnEmail
 * @specification https://datatracker.ietf.org/doc/html/rfc4952
 */
function IsIdnEmail(value        )          {
	return IdnEmail.test(value.normalize("NFC"));
}


/***/ }),
/* 32 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   IsIdnHostname: () => (/* binding */ IsIdnHostname)
/* harmony export */ });
/* harmony import */ var _idna_index_ts__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(33);
/*--------------------------------------------------------------------------

TypeBox

The MIT License (MIT)

Copyright (c) 2017-2026 Haydn Paterson

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

---------------------------------------------------------------------------*/



/**
 * Returns true if the value is a valid internationalized (IDN) hostname.
 * @specification https://tools.ietf.org/html/rfc3490
 * @specification https://tools.ietf.org/html/rfc5891
 * @specification https://tools.ietf.org/html/rfc5892
 * @specification https://tools.ietf.org/html/rfc5893
 */
function IsIdnHostname(value        )          {
	return _idna_index_ts__WEBPACK_IMPORTED_MODULE_0__.IsIdnHostname(value);
}


/***/ }),
/* 33 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   IsIdnHostname: () => (/* binding */ IsIdnHostname)
/* harmony export */ });
/* harmony import */ var _format_bidi_ts__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(30);
/* harmony import */ var _label_puny_ts__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(27);
/* harmony import */ var _label_unicode_ts__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(29);
/*--------------------------------------------------------------------------

TypeBox

The MIT License (MIT)

Copyright (c) 2017-2026 Haydn Paterson

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

---------------------------------------------------------------------------*/





// ------------------------------------------------------------------
// IsLabel
// ------------------------------------------------------------------
function IsValidLabelLength(value        )          {
	return value.length > 0 && value.length <= 63;
}
function IsLabel(value        )          {
	return IsValidLabelLength(value) && (_label_puny_ts__WEBPACK_IMPORTED_MODULE_1__.IsPunyLabel(value) || _label_unicode_ts__WEBPACK_IMPORTED_MODULE_2__.IsUnicodeLabel(value));
}
// ------------------------------------------------------------------
// NormalizeHostname
//
// Normalizes a hostname for label validation. Maps fullwidth
// codepoints to their ASCII equivalent (e.g. 'ａ' U+FF41 maps to
// 'a'), then normalizes for NFC such that equivalent codepoint
// sequences can be uniformly compared. It also removes codepoints
// that IDNA mapping ignores, and converts full stop variants to
// ASCII '.' so the hostname can be split into labels.
// ------------------------------------------------------------------
function NormalizeHostname(value        )         {
	return (
		value
			.replace(/[\uff01-\uff5e]/g, (char) => String.fromCharCode(char.charCodeAt(0) - 0xfee0)) // RE_FULLWIDTH_MAPPING
			.normalize("NFC")
			// biome-ignore lint/suspicious/noMisleadingCharacterClass: a set of single code points to remove (u flag), by design.
			.replace(/[\u00ad\u034f\u180b-\u180d\u200b\ufe00-\ufe0f\u{e0100}-\u{e01ef}]/gu, "") // RE_IGNORED_MAPPING
			.replace(/[\u002E\u3002\uFF0E\uFF61]/g, ".")
	); // RE_FULL_STOP_MAPPING
}
// ------------------------------------------------------------------
// IsIdnHostname
// ------------------------------------------------------------------
function IsIdnHostname(value        )          {
	if (value.length === 0 || value.includes(" ")) return false;
	const normalized = NormalizeHostname(value);
	if (normalized.length > 253) return false;
	const labels = normalized.split(".");
	const hasBidiChars = labels.some((label) => _format_bidi_ts__WEBPACK_IMPORTED_MODULE_0__.HasBidiChars(label));
	return labels.every((label) => IsLabel(label) && (!hasBidiChars || _format_bidi_ts__WEBPACK_IMPORTED_MODULE_0__.SatisfiesBidiRule(label)));
}


/***/ }),
/* 34 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   IsIPv4: () => (/* binding */ IsIPv4)
/* harmony export */ });
/*--------------------------------------------------------------------------

TypeBox

The MIT License (MIT)

Copyright (c) 2017-2026 Haydn Paterson

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

---------------------------------------------------------------------------*/

const IPv4 = /^(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$/;

/**
 * Returns true if the value is a IPV4 address
 * @specification http://tools.ietf.org/html/rfc2673#section-3.2
 */
function IsIPv4(value        )          {
	return IPv4.test(value);
}


/***/ }),
/* 35 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   IsIPv6: () => (/* binding */ IsIPv6)
/* harmony export */ });
/*--------------------------------------------------------------------------

TypeBox

The MIT License (MIT)

Copyright (c) 2017-2026 Haydn Paterson

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

---------------------------------------------------------------------------*/

const IPv6 =
	/^(?:(?:(?:[0-9a-f]{1,4}:){6}|::(?:[0-9a-f]{1,4}:){5}|(?:[0-9a-f]{1,4})?::(?:[0-9a-f]{1,4}:){4}|(?:(?:[0-9a-f]{1,4}:)?[0-9a-f]{1,4})?::(?:[0-9a-f]{1,4}:){3}|(?:(?:[0-9a-f]{1,4}:){0,2}[0-9a-f]{1,4})?::(?:[0-9a-f]{1,4}:){2}|(?:(?:[0-9a-f]{1,4}:){0,3}[0-9a-f]{1,4})?::[0-9a-f]{1,4}:|(?:(?:[0-9a-f]{1,4}:){0,4}[0-9a-f]{1,4})?::)(?:[0-9a-f]{1,4}:[0-9a-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:(?:[0-9a-f]{1,4}:){0,5}[0-9a-f]{1,4})?::[0-9a-f]{1,4}|(?:(?:[0-9a-f]{1,4}:){0,6}[0-9a-f]{1,4})?::)$/i;

/**
 * Returns true if the value is an IPv6 address
 * @specification http://tools.ietf.org/html/rfc2373#section-2.2
 */
function IsIPv6(value        )          {
	return IPv6.test(value);
}


/***/ }),
/* 36 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   IsIri: () => (/* binding */ IsIri)
/* harmony export */ });
/*--------------------------------------------------------------------------

TypeBox

The MIT License (MIT)

Copyright (c) 2017-2026 Haydn Paterson

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

---------------------------------------------------------------------------*/

// deno-lint-ignore-file no-control-regex

const IpvFutureMatchMaxLength = 2048;
const IpvFutureMatch = /\[[vV][0-9a-fA-F]+\.[^\]]+\]/; // Guarded By IpvFutureMatchMaxLength
const InvalidIriChars = /[\x00-\x20<>^`{|}\\]/;
const InvalidPercentEncoding = /%(?![0-9a-fA-F]{2})/;

// ------------------------------------------------------------------
// NarrowIpvFuture
//
// Substitutes an IPvFuture address with a standard IPv6 loopback
// address ([::1]). We do this because the native URL.canParse
// (WHATWG standard) rejects IPvFuture literals, which are
// otherwise valid in RFC 3987. Because regex substitution can
// be expensive on large strings, this operation is strictly
// limited to inputs under a defined length threshold.
//
// (review-optimization)
//
// ------------------------------------------------------------------
function NarrowIpvFuture(value        )         {
	return value.length < IpvFutureMatchMaxLength ? value.replace(IpvFutureMatch, "[::1]") : value;
}
/**
 * Returns true if the value is a valid Internationalized Resource Identifier.
 * @specification https://datatracker.ietf.org/doc/html/rfc3987
 */
function IsIri(value        )          {
	// 1. Reject strings containing unencoded whitespace or illegal control characters.
	if (InvalidIriChars.test(value)) return false;
	// 2. Reject malformed percent-encoding triplets.
	if (InvalidPercentEncoding.test(value)) return false;
	// 3. Delegate to the native URL parser, patching the IPvFuture edge case beforehand.
	return URL.canParse(NarrowIpvFuture(value));
}


/***/ }),
/* 37 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   IsIriReference: () => (/* binding */ IsIriReference)
/* harmony export */ });
/*--------------------------------------------------------------------------

TypeBox

The MIT License (MIT)

Copyright (c) 2017-2026 Haydn Paterson

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

---------------------------------------------------------------------------*/

// deno-lint-ignore-file no-control-regex

// Rejects unencoded spaces (0x20), backslashes, control characters (0x00-0x1F, 0x7F), or malformed percent-encodings
const InvalidIriChars = /[\x00-\x20\x7F\\]|%(?![0-9a-fA-F]{2})/;
// Detects malformed scheme/authority patterns lacking a colon (e.g. "httpx//example.com")
const MalformedScheme = /^[a-zA-Z][a-zA-Z0-9+\-.]*\/\//;

/**
 * Returns true if the value is an IRI reference
 * @specification https://tools.ietf.org/html/rfc3987
 */
function IsIriReference(value        )          {
	return !InvalidIriChars.test(value) && !MalformedScheme.test(value) && URL.canParse(value, "http://example.com");
}


/***/ }),
/* 38 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   IsJsonPointer: () => (/* binding */ IsJsonPointer)
/* harmony export */ });
/*--------------------------------------------------------------------------

TypeBox

The MIT License (MIT)

Copyright (c) 2017-2026 Haydn Paterson

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

---------------------------------------------------------------------------*/

const JsonPointer = /^(?:\/(?:[^~/]|~0|~1)*)*$/;

/**
 * Returns true if the value is a json pointer
 * @specification https://datatracker.ietf.org/doc/html/rfc6901
 * @source https://github.com/ajv-validator/ajv-formats
 */
function IsJsonPointer(value        )          {
	return JsonPointer.test(value);
}


/***/ }),
/* 39 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   IsJsonPointerUriFragment: () => (/* binding */ IsJsonPointerUriFragment)
/* harmony export */ });
/*--------------------------------------------------------------------------

TypeBox

The MIT License (MIT)

Copyright (c) 2017-2026 Haydn Paterson

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

---------------------------------------------------------------------------*/

const JsonPointerUriFragment = /^#(?:\/(?:[a-z0-9_\-.!$&'()*+,;:=@]|%[0-9a-f]{2}|~0|~1)*)*$/i;

/**
 * Returns true if the value is a json pointer uri fragment
 * @specification https://datatracker.ietf.org/doc/html/rfc6901
 * @source https://github.com/ajv-validator/ajv-formats
 */
function IsJsonPointerUriFragment(value        )          {
	return JsonPointerUriFragment.test(value);
}


/***/ }),
/* 40 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   IsRegex: () => (/* binding */ IsRegex)
/* harmony export */ });
/*--------------------------------------------------------------------------

TypeBox

The MIT License (MIT)

Copyright (c) 2017-2026 Haydn Paterson

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

---------------------------------------------------------------------------*/

/**
 * Returns true if the value is a regular expression string pattern
 * @specification https://ecma-international.org/ecma-262
 */
function IsRegex(value        )          {
	try {
		new RegExp(value, "u");
		return true;
	} catch {
		return false;
	}
}


/***/ }),
/* 41 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   IsRelativeJsonPointer: () => (/* binding */ IsRelativeJsonPointer)
/* harmony export */ });
/*--------------------------------------------------------------------------

TypeBox

The MIT License (MIT)

Copyright (c) 2017-2026 Haydn Paterson

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

---------------------------------------------------------------------------*/

const RelativeJsonPointer = /^(?:0|[1-9][0-9]*)(?:#|(?:\/(?:[^~/]|~0|~1)*)*)$/;

/**
 * Returns true if the value is a relative json pointer
 * @specification https://datatracker.ietf.org/doc/html/rfc6901
 * @source https://github.com/ajv-validator/ajv-formats
 */
function IsRelativeJsonPointer(value        )          {
	return RelativeJsonPointer.test(value);
}


/***/ }),
/* 42 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   IsUri: () => (/* binding */ IsUri)
/* harmony export */ });
/*--------------------------------------------------------------------------

TypeBox

The MIT License (MIT)

Copyright (c) 2017-2026 Haydn Paterson

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

---------------------------------------------------------------------------*/

const Uri =
	/^[a-z][a-z0-9+\-.]*:(?:\/\/(?:(?:[-a-z0-9._~!$&'()*+,;=:]|%[0-9a-f]{2})*@)?(?:\[(?:(?:(?:[\da-f]{1,4}:){6}(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|::(?:[\da-f]{1,4}:){5}(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:[\da-f]{1,4})?::(?:[\da-f]{1,4}:){4}(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:(?:[\da-f]{1,4}:){0,1}[\da-f]{1,4})?::(?:[\da-f]{1,4}:){3}(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:(?:[\da-f]{1,4}:){0,2}[\da-f]{1,4})?::(?:[\da-f]{1,4}:){2}(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:(?:[\da-f]{1,4}:){0,3}[\da-f]{1,4})?::[\da-f]{1,4}:(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:(?:[\da-f]{1,4}:){0,4}[\da-f]{1,4})?::(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:(?:[\da-f]{1,4}:){0,5}[\da-f]{1,4})?::[\da-f]{1,4}|(?:(?:[\da-f]{1,4}:){0,6}[\da-f]{1,4})?::)|v[0-9a-f]+\.[-a-z0-9._~!$&'()*+,;=:]+)\]|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)|(?:[-a-z0-9._~!$&'()*+,;=]|%[0-9a-f]{2})*)(?::\d*)?(?:\/(?:[-a-z0-9._~!$&'()*+,;=:@]|%[0-9a-f]{2})*)*|\/(?:(?:[-a-z0-9._~!$&'()*+,;=:@]|%[0-9a-f]{2})+(?:\/(?:[-a-z0-9._~!$&'()*+,;=:@]|%[0-9a-f]{2})*)*)?|(?:[-a-z0-9._~!$&'()*+,;=:@]|%[0-9a-f]{2})+(?:\/(?:[-a-z0-9._~!$&'()*+,;=:@]|%[0-9a-f]{2})*)*)?(?:\?(?:[-a-z0-9._~!$&'()*+,;=:@/?]|%[0-9a-f]{2})*)?(?:#(?:[-a-z0-9._~!$&'()*+,;=:@/?]|%[0-9a-f]{2})*)?$/i;

/**
 * Returns true if the value is a valid Uniform Resource Identifier.
 * @specification https://tools.ietf.org/html/rfc3986
 */
function IsUri(value        )          {
	return Uri.test(value);
}


/***/ }),
/* 43 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   IsUriReference: () => (/* binding */ IsUriReference)
/* harmony export */ });
/*--------------------------------------------------------------------------

TypeBox

The MIT License (MIT)

Copyright (c) 2017-2026 Haydn Paterson

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

---------------------------------------------------------------------------*/

const UriReference =
	/^(?:[a-z][a-z0-9+\-.]*:(?:\/\/(?:(?:[-a-z0-9._~!$&'()*+,;=:]|%[0-9a-f]{2})*@)?(?:\[(?:(?:(?:[\da-f]{1,4}:){6}(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|::(?:[\da-f]{1,4}:){5}(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:[\da-f]{1,4})?::(?:[\da-f]{1,4}:){4}(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:(?:[\da-f]{1,4}:){0,1}[\da-f]{1,4})?::(?:[\da-f]{1,4}:){3}(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:(?:[\da-f]{1,4}:){0,2}[\da-f]{1,4})?::(?:[\da-f]{1,4}:){2}(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:(?:[\da-f]{1,4}:){0,3}[\da-f]{1,4})?::[\da-f]{1,4}:(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:(?:[\da-f]{1,4}:){0,4}[\da-f]{1,4})?::(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:(?:[\da-f]{1,4}:){0,5}[\da-f]{1,4})?::[\da-f]{1,4}|(?:(?:[\da-f]{1,4}:){0,6}[\da-f]{1,4})?::)|v[0-9a-f]+\.[-a-z0-9._~!$&'()*+,;=:]+)\]|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)|(?:[-a-z0-9._~!$&'()*+,;=]|%[0-9a-f]{2})*)(?::\d*)?(?:\/(?:[-a-z0-9._~!$&'()*+,;=:@]|%[0-9a-f]{2})*)*|\/(?:(?:[-a-z0-9._~!$&'()*+,;=:@]|%[0-9a-f]{2})+(?:\/(?:[-a-z0-9._~!$&'()*+,;=:@]|%[0-9a-f]{2})*)*)?|(?:[-a-z0-9._~!$&'()*+,;=:@]|%[0-9a-f]{2})+(?:\/(?:[-a-z0-9._~!$&'()*+,;=:@]|%[0-9a-f]{2})*)*)?|(?:\/\/(?:(?:[-a-z0-9._~!$&'()*+,;=:]|%[0-9a-f]{2})*@)?(?:\[(?:(?:(?:[\da-f]{1,4}:){6}(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|::(?:[\da-f]{1,4}:){5}(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:[\da-f]{1,4})?::(?:[\da-f]{1,4}:){4}(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:(?:[\da-f]{1,4}:){0,1}[\da-f]{1,4})?::(?:[\da-f]{1,4}:){3}(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:(?:[\da-f]{1,4}:){0,2}[\da-f]{1,4})?::(?:[\da-f]{1,4}:){2}(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:(?:[\da-f]{1,4}:){0,3}[\da-f]{1,4})?::[\da-f]{1,4}:(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:(?:[\da-f]{1,4}:){0,4}[\da-f]{1,4})?::(?:[\da-f]{1,4}:[\da-f]{1,4}|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d))|(?:(?:[\da-f]{1,4}:){0,5}[\da-f]{1,4})?::[\da-f]{1,4}|(?:(?:[\da-f]{1,4}:){0,6}[\da-f]{1,4})?::)|v[0-9a-f]+\.[-a-z0-9._~!$&'()*+,;=:]+)\]|(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)|(?:[-a-z0-9._~!$&'()*+,;=]|%[0-9a-f]{2})*)(?::\d*)?(?:\/(?:[-a-z0-9._~!$&'()*+,;=:@]|%[0-9a-f]{2})*)*|\/(?:(?:[-a-z0-9._~!$&'()*+,;=:@]|%[0-9a-f]{2})+(?:\/(?:[-a-z0-9._~!$&'()*+,;=:@]|%[0-9a-f]{2})*)*)?|(?:[-a-z0-9._~!$&'()*+,;=@]|%[0-9a-f]{2})+(?:\/(?:[-a-z0-9._~!$&'()*+,;=:@]|%[0-9a-f]{2})*)*)?)(?:\?(?:[-a-z0-9._~!$&'()*+,;=:@/?]|%[0-9a-f]{2})*)?(?:#(?:[-a-z0-9._~!$&'()*+,;=:@/?]|%[0-9a-f]{2})*)?$/i;

/**
 * Returns true if the value is a valid Uri Reference.
 * @specification https://tools.ietf.org/html/rfc3986
 */
function IsUriReference(value        )          {
	return UriReference.test(value);
}


/***/ }),
/* 44 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   IsUriTemplate: () => (/* binding */ IsUriTemplate)
/* harmony export */ });
/*--------------------------------------------------------------------------

TypeBox

The MIT License (MIT)

Copyright (c) 2017-2026 Haydn Paterson

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

---------------------------------------------------------------------------*/

// deno-lint-ignore-file no-control-regex

const UriTemplate =
	/^(?:(?:[^\x00-\x20"<>%\\^`{|}\x7f]|%[0-9a-f]{2})|\{[+#./;?&=,!@|]?(?:[a-z0-9_]|%[0-9a-f]{2})+(?:\.(?:[a-z0-9_]|%[0-9a-f]{2})+)*(?::[1-9]\d{0,3}|\*)?(?:,(?:[a-z0-9_]|%[0-9a-f]{2})+(?:\.(?:[a-z0-9_]|%[0-9a-f]{2})+)*(?::[1-9]\d{0,3}|\*)?)*\})*$/i;

/**
 * Returns true if the value is a valid Uri Template
 * @specification https://datatracker.ietf.org/doc/html/rfc6570
 */
function IsUriTemplate(value        )          {
	return UriTemplate.test(value);
}


/***/ }),
/* 45 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   IsUrl: () => (/* binding */ IsUrl)
/* harmony export */ });
/*--------------------------------------------------------------------------

TypeBox

The MIT License (MIT)

Copyright (c) 2017-2026 Haydn Paterson

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

---------------------------------------------------------------------------*/

/**
 * Returns true if the value is a valid Uniform Resource Locator
 * @specification https://datatracker.ietf.org/doc/html/rfc3986
 * @specification https://datatracker.ietf.org/doc/html/rfc3987
 */
function IsUrl(value        )          {
	return URL.canParse(value);
}


/***/ }),
/* 46 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   IsUuid: () => (/* binding */ IsUuid)
/* harmony export */ });
/*--------------------------------------------------------------------------

TypeBox

The MIT License (MIT)

Copyright (c) 2017-2026 Haydn Paterson

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

---------------------------------------------------------------------------*/

const Uuid = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i;

/**
 * Returns true if the value is a uuid
 * @specification https://www.rfc-editor.org/info/rfc4122/
 * @specification https://www.rfc-editor.org/info/rfc9562/
 */
function IsUuid(value        )          {
	return Uuid.test(value);
}


/***/ }),
/* 47 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   DEFAULT_AGENT_IDENTITY: () => (/* binding */ DEFAULT_AGENT_IDENTITY),
/* harmony export */   agentLoop: () => (/* binding */ agentLoop),
/* harmony export */   agentLoopContinue: () => (/* binding */ agentLoopContinue),
/* harmony export */   runAgentLoop: () => (/* binding */ runAgentLoop),
/* harmony export */   runAgentLoopContinue: () => (/* binding */ runAgentLoopContinue),
/* harmony export */   runToolCall: () => (/* binding */ runToolCall)
/* harmony export */ });
/* harmony import */ var _earendil_works_pi_ai__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(2);
/* harmony import */ var _stream_fn_ts__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(48);
/**
 * Agent loop that works with AgentMessage throughout.
 * Transforms to Message[] only at the LLM call boundary.
 */



             
	             
	           
	                
	             
	          
	              
	                     
	                
	                       
	         
                    

                                                                         

/**
 * Request identity of agent loops: pi-ai's defaults with provider attribution to pi. `AgentLoopConfig.identity`
 * overrides it field by field.
 */
const DEFAULT_AGENT_IDENTITY                 = { attribution: _earendil_works_pi_ai__WEBPACK_IMPORTED_MODULE_0__.PI_ATTRIBUTION };

/**
 * Start an agent loop with a new prompt message.
 * The prompt is added to the context and events are emitted for it.
 */
function agentLoop(
	prompts                ,
	context              ,
	config                 ,
	signal                         ,
	streamFn          ,
)                                          {
	const stream = createAgentStream();

	void runAgentLoop(
		prompts,
		context,
		config,
		async (event) => {
			stream.push(event);
		},
		signal,
		streamFn,
	).then((messages) => {
		stream.end(messages);
	});

	return stream;
}

/**
 * Continue an agent loop from the current context without adding a new message.
 * Used for retries - context already has user message or tool results.
 *
 * **Important:** The last message in context must convert to a `user` or `toolResult` message
 * via `convertToLlm`. If it doesn't, the LLM provider will reject the request.
 * This cannot be validated here since `convertToLlm` is only called once per turn.
 */
function agentLoopContinue(
	context              ,
	config                 ,
	signal                         ,
	streamFn          ,
)                                          {
	if (context.messages.length === 0) {
		throw new Error("Cannot continue: no messages in context");
	}

	if (context.messages[context.messages.length - 1].role === "assistant") {
		throw new Error("Cannot continue from message role: assistant");
	}

	const stream = createAgentStream();

	void runAgentLoopContinue(
		context,
		config,
		async (event) => {
			stream.push(event);
		},
		signal,
		streamFn,
	).then((messages) => {
		stream.end(messages);
	});

	return stream;
}

async function runAgentLoop(
	prompts                ,
	context              ,
	config                 ,
	emit                ,
	signal                         ,
	streamFn          ,
)                          {
	const initialMessages = declareToolChanges(context, prompts);
	const newMessages                 = [...initialMessages];
	const currentContext               = {
		...context,
		messages: [...context.messages, ...initialMessages],
	};

	await emit({ type: "agent_start" });
	await emit({ type: "turn_start" });
	for (const message of initialMessages) {
		await emit({ type: "message_start", message });
		await emit({ type: "message_end", message });
	}

	await runLoop(currentContext, newMessages, config, signal, emit, streamFn ?? (0,_stream_fn_ts__WEBPACK_IMPORTED_MODULE_1__.getDefaultStreamFn)());
	return newMessages;
}

async function runAgentLoopContinue(
	context              ,
	config                 ,
	emit                ,
	signal                         ,
	streamFn          ,
)                          {
	if (context.messages.length === 0) {
		throw new Error("Cannot continue: no messages in context");
	}

	if (context.messages[context.messages.length - 1].role === "assistant") {
		throw new Error("Cannot continue from message role: assistant");
	}

	const newMessages                 = [];
	const currentContext               = { ...context };

	await emit({ type: "agent_start" });
	await emit({ type: "turn_start" });

	await runLoop(currentContext, newMessages, config, signal, emit, streamFn ?? (0,_stream_fn_ts__WEBPACK_IMPORTED_MODULE_1__.getDefaultStreamFn)());
	return newMessages;
}

function createAgentStream()                                          {
	return new _earendil_works_pi_ai__WEBPACK_IMPORTED_MODULE_0__.EventStream                            (
		(event            ) => event.type === "agent_end",
		(event            ) => (event.type === "agent_end" ? event.messages : []),
	);
}

/**
 * Main loop logic shared by agentLoop and agentLoopContinue.
 */
async function runLoop(
	initialContext              ,
	newMessages                ,
	initialConfig                 ,
	signal                         ,
	emit                ,
	streamFunction          ,
)                {
	let currentContext = initialContext;
	let config = initialConfig;
	let lastCompletedTurn                                    ;
	let explicitContinuation = false;
	// Check for steering messages at start (user may have typed while waiting)
	let pendingMessages                 = (await config.getSteeringMessages?.()) || [];

	// Outer loop: continues when queued follow-up messages arrive after agent would stop
	while (true) {
		let hasMoreToolCalls = true;

		// Inner loop: process tool calls and steering messages
		while (hasMoreToolCalls || pendingMessages.length > 0) {
			let preparedMessages                 = [];
			if (lastCompletedTurn) {
				const nextTurnSnapshot = await config.prepareNextTurn?.(lastCompletedTurn);
				if (nextTurnSnapshot) {
					currentContext = nextTurnSnapshot.context ?? currentContext;
					preparedMessages = nextTurnSnapshot.messages ?? [];
					config = {
						...config,
						model: nextTurnSnapshot.model ?? config.model,
						reasoning:
							nextTurnSnapshot.thinkingLevel === undefined
								? config.reasoning
								: nextTurnSnapshot.thinkingLevel === "off"
									? undefined
									: nextTurnSnapshot.thinkingLevel,
					};
				}
				// Preparation can be long-running (for example, compaction). Pick up steering
				// queued while it ran. Only poll again if the earlier poll returned nothing;
				// otherwise one-at-a-time mode would deliver two messages in this turn.
				if (pendingMessages.length === 0) {
					pendingMessages = (await config.getSteeringMessages?.()) || [];
				}
				await emit({ type: "turn_start" });
			}

			// Process prepared and queued messages before the next assistant response.
			for (const message of declareToolChanges(currentContext, [...preparedMessages, ...pendingMessages])) {
				await emit({ type: "message_start", message });
				await emit({ type: "message_end", message });
				currentContext.messages.push(message);
				newMessages.push(message);
			}
			pendingMessages = [];

			const requestUpdate = await config.prepareRequest?.(
				{
					context: currentContext,
					model: config.model,
					thinkingLevel: config.reasoning ?? "off",
				},
				signal,
			);
			if (requestUpdate) {
				currentContext = requestUpdate.context ?? currentContext;
				config = {
					...config,
					model: requestUpdate.model ?? config.model,
					reasoning:
						requestUpdate.thinkingLevel === undefined
							? config.reasoning
							: requestUpdate.thinkingLevel === "off"
								? undefined
								: requestUpdate.thinkingLevel,
				};
			}

			// Stream assistant response
			const message = await streamAssistantResponse(currentContext, config, signal, emit, streamFunction);
			newMessages.push(message);

			if (message.stopReason === "error" || message.stopReason === "aborted") {
				lastCompletedTurn = {
					message,
					toolResults: [],
					context: currentContext,
					newMessages,
				};
				await config.finishTurn?.(lastCompletedTurn, signal);
				await emit({ type: "turn_end", message, toolResults: [] });
				await emit({ type: "agent_end", messages: newMessages });
				return;
			}

			// Check for tool calls
			const toolCalls = message.content.filter((c) => c.type === "toolCall");

			const toolResults                      = [];
			hasMoreToolCalls = false;
			if (toolCalls.length > 0) {
				// A "length" stop means the output was cut off by the token limit, so
				// every tool call in the message may carry truncated arguments. Fail
				// them all instead of executing potentially borked calls.
				const executedToolBatch =
					message.stopReason === "length"
						? await failToolCallsFromTruncatedMessage(toolCalls, emit)
						: await executeToolCalls(currentContext, message, config, signal, emit);
				toolResults.push(...executedToolBatch.messages);
				hasMoreToolCalls = !executedToolBatch.terminate;

				for (const result of toolResults) {
					currentContext.messages.push(result);
					newMessages.push(result);
				}
			}

			lastCompletedTurn = {
				message,
				toolResults,
				context: currentContext,
				newMessages,
			};
			const decision = await config.finishTurn?.(lastCompletedTurn, signal);
			await emit({ type: "turn_end", message, toolResults });

			if (decision?.action === "end") {
				await emit({ type: "agent_end", messages: newMessages });
				return;
			}

			explicitContinuation = decision?.action === "continue";
			pendingMessages = (await config.getSteeringMessages?.()) || [];
			if (hasMoreToolCalls || pendingMessages.length > 0) {
				explicitContinuation = false;
			}
		}

		// Agent would stop here. Check for follow-up messages.
		const followUpMessages = (await config.getFollowUpMessages?.()) || [];
		if (followUpMessages.length > 0) {
			// Set as pending so inner loop processes them
			explicitContinuation = false;
			pendingMessages = followUpMessages;
			continue;
		}

		// No natural request was selected, so fulfill the continuation decision with one context-only turn.
		if (explicitContinuation) {
			explicitContinuation = false;
			continue;
		}

		// No more messages, exit
		break;
	}

	await emit({ type: "agent_end", messages: newMessages });
}

/**
 * Declare tool loadout changes to the model.
 *
 * `context.tools` is what the runtime can execute; the transcript's system messages declare
 * what the model may call. Before each request the difference becomes `toolsAdded` and
 * `toolsRemoved` on a system message. When a pending system message exists, its tool fields
 * are treated as intent and replaced with the delta between the committed transcript and
 * the executable set, so replay always yields exactly `context.tools`. Otherwise a new
 * system message is inserted before the first non-system pending message.
 */
function declareToolChanges(context              , pendingMessages                )                 {
	let systemIndex = -1;
	for (let i = pendingMessages.length - 1; i >= 0; i--) {
		if (pendingMessages[i].role === "system") {
			systemIndex = i;
			break;
		}
	}
	const pending = pendingMessages[systemIndex]                             ;
	const baseline = pending
		? pendingMessages.map((message, index) =>
				index === systemIndex ? withToolChanges(pending, NO_CHANGES) : message,
			)
		: pendingMessages;
	const changes = (0,_earendil_works_pi_ai__WEBPACK_IMPORTED_MODULE_0__.getToolStateChanges)(
		(0,_earendil_works_pi_ai__WEBPACK_IMPORTED_MODULE_0__.getCurrentTools)([...context.messages, ...baseline]),
		(context.tools ?? []).map(_earendil_works_pi_ai__WEBPACK_IMPORTED_MODULE_0__.toToolDeclaration),
	);
	const unchanged = changes.toolsAdded.length === 0 && changes.toolsRemoved.length === 0;

	if (pending) {
		// Keep the caller's message object when it already declares no tool changes.
		if (unchanged && !pending.toolsAdded?.length && !pending.toolsRemoved?.length) return pendingMessages;
		return baseline.map((message, index) => (index === systemIndex ? withToolChanges(pending, changes) : message));
	}
	if (unchanged) return pendingMessages;
	const update = withToolChanges({ role: "system", content: "", timestamp: Date.now() }, changes);
	const insertIndex = pendingMessages.findIndex((message) => message.role !== "system");
	const index = insertIndex === -1 ? pendingMessages.length : insertIndex;
	return [...pendingMessages.slice(0, index), update, ...pendingMessages.slice(index)];
}

const NO_CHANGES                   = { toolsAdded: [], toolsRemoved: [] };

/** Copy a system message with its tool fields replaced by `changes`; empty lists omit the field. */
function withToolChanges(message               , { toolsAdded, toolsRemoved }                  )                {
	const { toolsAdded: _added, toolsRemoved: _removed, ...rest } = message;
	return {
		...rest,
		...(toolsAdded.length > 0 ? { toolsAdded } : {}),
		...(toolsRemoved.length > 0 ? { toolsRemoved } : {}),
	};
}

/**
 * Stream an assistant response from the LLM.
 * This is where AgentMessage[] gets transformed to Message[] for the LLM.
 */
async function streamAssistantResponse(
	context              ,
	config                 ,
	signal                         ,
	emit                ,
	streamFunction          ,
)                            {
	// Apply context transform if configured (AgentMessage[] → AgentMessage[])
	let messages = context.messages;
	if (config.transformContext) {
		messages = await config.transformContext(messages, signal);
	}

	// Convert to LLM-compatible messages (AgentMessage[] → Message[])
	const llmMessages = await config.convertToLlm(messages);

	const llmContext = (0,_earendil_works_pi_ai__WEBPACK_IMPORTED_MODULE_0__.normalizeContext)({ messages: llmMessages });

	// Resolve API key (important for expiring tokens)
	const resolvedApiKey =
		(config.getApiKey ? await config.getApiKey(config.model.provider) : undefined) || config.apiKey;

	const response = await streamFunction(config.model, llmContext, {
		...config,
		apiKey: resolvedApiKey,
		identity: (0,_earendil_works_pi_ai__WEBPACK_IMPORTED_MODULE_0__.mergeClientIdentity)(DEFAULT_AGENT_IDENTITY, config.identity),
		signal,
	});
	// Record the requested level, whichever stream function answered.
	const result = async () => Object.assign(await response.result(), { thinkingLevel: config.reasoning ?? "off" });

	let partialMessage                          = null;
	let addedPartial = false;

	for await (const event of response) {
		switch (event.type) {
			case "start":
				partialMessage = event.partial;
				context.messages.push(partialMessage);
				addedPartial = true;
				await emit({ type: "message_start", message: { ...partialMessage } });
				break;

			case "text_start":
			case "text_delta":
			case "text_end":
			case "thinking_start":
			case "thinking_delta":
			case "thinking_end":
			case "toolcall_start":
			case "toolcall_delta":
			case "toolcall_end":
				if (partialMessage) {
					partialMessage = event.partial;
					context.messages[context.messages.length - 1] = partialMessage;
					await emit({
						type: "message_update",
						assistantMessageEvent: event,
						message: { ...partialMessage },
					});
				}
				break;

			case "done":
			case "error": {
				const finalMessage = await result();
				if (addedPartial) {
					context.messages[context.messages.length - 1] = finalMessage;
				} else {
					context.messages.push(finalMessage);
				}
				if (!addedPartial) {
					await emit({ type: "message_start", message: { ...finalMessage } });
				}
				await emit({ type: "message_end", message: finalMessage });
				return finalMessage;
			}
		}
	}

	const finalMessage = await result();
	if (addedPartial) {
		context.messages[context.messages.length - 1] = finalMessage;
	} else {
		context.messages.push(finalMessage);
		await emit({ type: "message_start", message: { ...finalMessage } });
	}
	await emit({ type: "message_end", message: finalMessage });
	return finalMessage;
}

/**
 * Fail all tool calls from an assistant message that was truncated by the
 * output token limit. Streamed tool-call arguments are finalized with a
 * best-effort JSON salvage parser, so a truncated message can yield tool calls
 * whose arguments parse and validate but are silently incomplete. None of them
 * are safe to execute; report each as an error so the model can re-issue them.
 */
async function failToolCallsFromTruncatedMessage(
	toolCalls                 ,
	emit                ,
)                                 {
	const messages                      = [];
	for (const toolCall of toolCalls) {
		await emit({
			type: "tool_execution_start",
			toolCallId: toolCall.id,
			toolName: toolCall.name,
			args: toolCall.arguments,
		});
		const finalized                           = {
			toolCall,
			result: createErrorToolResult(
				`Tool call "${toolCall.name}" was not executed: the response hit the output token limit, so its arguments may be truncated. Re-issue the tool call with complete arguments.`,
			),
			isError: true,
		};
		await emitToolExecutionEnd(finalized, emit);
		const toolResultMessage = createToolResultMessage(finalized);
		await emitToolResultMessage(toolResultMessage, emit);
		messages.push(toolResultMessage);
	}
	return { messages, terminate: false };
}

/**
 * Execute tool calls from an assistant message.
 */
async function executeToolCalls(
	currentContext              ,
	assistantMessage                  ,
	config                 ,
	signal                         ,
	emit                ,
)                                 {
	const toolCalls = assistantMessage.content.filter((c) => c.type === "toolCall");
	const hasSequentialToolCall = toolCalls.some(
		(tc) => currentContext.tools?.find((t) => t.name === tc.name)?.executionMode === "sequential",
	);
	if (config.toolExecution === "sequential" || hasSequentialToolCall) {
		return executeToolCallsSequential(currentContext, assistantMessage, toolCalls, config, signal, emit);
	}
	return executeToolCallsParallel(currentContext, assistantMessage, toolCalls, config, signal, emit);
}

                              
	                              
	                   
  

async function executeToolCallsSequential(
	currentContext              ,
	assistantMessage                  ,
	toolCalls                 ,
	config                 ,
	signal                         ,
	emit                ,
)                                 {
	const finalizedCalls                             = [];
	const messages                      = [];

	for (const toolCall of toolCalls) {
		await emit({
			type: "tool_execution_start",
			toolCallId: toolCall.id,
			toolName: toolCall.name,
			args: toolCall.arguments,
		});

		const preparation = await prepareToolCall(currentContext, assistantMessage, toolCall, config, signal);
		let finalized                          ;
		if (preparation.kind === "immediate") {
			finalized = {
				toolCall,
				result: preparation.result,
				isError: preparation.isError,
			};
		} else {
			const executed = await executePreparedToolCall(preparation, signal, emitToolExecutionUpdate(toolCall, emit));
			finalized = await finalizeExecutedToolCall(
				currentContext,
				assistantMessage,
				preparation,
				executed,
				config,
				signal,
			);
		}

		await emitToolExecutionEnd(finalized, emit);
		const toolResultMessage = createToolResultMessage(finalized);
		await emitToolResultMessage(toolResultMessage, emit);
		finalizedCalls.push(finalized);
		messages.push(toolResultMessage);

		if (signal?.aborted) {
			break;
		}
	}

	return {
		messages,
		terminate: shouldTerminateToolBatch(finalizedCalls),
	};
}

async function executeToolCallsParallel(
	currentContext              ,
	assistantMessage                  ,
	toolCalls                 ,
	config                 ,
	signal                         ,
	emit                ,
)                                 {
	const finalizedCalls                           = [];

	for (const toolCall of toolCalls) {
		await emit({
			type: "tool_execution_start",
			toolCallId: toolCall.id,
			toolName: toolCall.name,
			args: toolCall.arguments,
		});

		const preparation = await prepareToolCall(currentContext, assistantMessage, toolCall, config, signal);
		if (preparation.kind === "immediate") {
			const finalized = {
				toolCall,
				result: preparation.result,
				isError: preparation.isError,
			}                                   ;
			await emitToolExecutionEnd(finalized, emit);
			finalizedCalls.push(finalized);
			if (signal?.aborted) {
				break;
			}
			continue;
		}

		finalizedCalls.push(async () => {
			if (signal?.aborted) {
				const finalized = {
					toolCall,
					result: createErrorToolResult("Operation aborted"),
					isError: true,
				}                                   ;
				await emitToolExecutionEnd(finalized, emit);
				return finalized;
			}
			const executed = await executePreparedToolCall(preparation, signal, emitToolExecutionUpdate(toolCall, emit));
			const finalized = await finalizeExecutedToolCall(
				currentContext,
				assistantMessage,
				preparation,
				executed,
				config,
				signal,
			);
			await emitToolExecutionEnd(finalized, emit);
			return finalized;
		});
		if (signal?.aborted) {
			break;
		}
	}

	const orderedFinalizedCalls = await Promise.all(
		finalizedCalls.map((entry) => (typeof entry === "function" ? entry() : Promise.resolve(entry))),
	);
	const messages                      = [];
	for (const finalized of orderedFinalizedCalls) {
		const toolResultMessage = createToolResultMessage(finalized);
		await emitToolResultMessage(toolResultMessage, emit);
		messages.push(toolResultMessage);
	}

	return {
		messages,
		terminate: shouldTerminateToolBatch(orderedFinalizedCalls),
	};
}

                         
	                 
	                        
	                     
	              
  

                                 
	                  
	                             
	                 
  

                                
	                             
	                 
	                   
  

                                                     

/** The `beforeToolCall` and `afterToolCall` hooks of {@link AgentLoopConfig}. */
                                                                                      

                                                                                    

                                                                                                   

function shouldTerminateToolBatch(finalizedCalls                            )          {
	return finalizedCalls.length > 0 && finalizedCalls.every((finalized) => finalized.result.terminate === true);
}

function prepareToolCallArguments(tool                , toolCall               )                {
	if (!tool.prepareArguments) {
		return toolCall;
	}
	const preparedArguments = tool.prepareArguments(toolCall.arguments);
	if (preparedArguments === toolCall.arguments) {
		return toolCall;
	}
	return {
		...toolCall,
		arguments: preparedArguments                       ,
	};
}

async function prepareToolCall(
	currentContext              ,
	assistantMessage                  ,
	toolCall               ,
	config               ,
	signal                         ,
	tools                            = currentContext.tools ?? [],
)                                                       {
	const tool = tools.find((t) => t.name === toolCall.name);
	if (!tool) {
		return {
			kind: "immediate",
			result: createErrorToolResult(`Tool ${toolCall.name} not found`),
			isError: true,
		};
	}

	try {
		const preparedToolCall = prepareToolCallArguments(tool, toolCall);
		const validatedArgs = (0,_earendil_works_pi_ai__WEBPACK_IMPORTED_MODULE_0__.validateToolArguments)(tool, preparedToolCall);
		if (config.beforeToolCall) {
			const beforeResult = await config.beforeToolCall(
				{
					assistantMessage,
					toolCall,
					args: validatedArgs,
					context: currentContext,
				},
				signal,
			);
			if (signal?.aborted) {
				return {
					kind: "immediate",
					result: createErrorToolResult("Operation aborted"),
					isError: true,
				};
			}
			if (beforeResult?.block) {
				const result = createErrorToolResult(beforeResult.reason || "Tool execution was blocked");
				if (beforeResult.terminate === true) {
					result.terminate = true;
				}
				return {
					kind: "immediate",
					result,
					isError: true,
				};
			}
		}
		if (signal?.aborted) {
			return {
				kind: "immediate",
				result: createErrorToolResult("Operation aborted"),
				isError: true,
			};
		}
		return {
			kind: "prepared",
			toolCall,
			tool,
			args: validatedArgs,
		};
	} catch (error) {
		return {
			kind: "immediate",
			result: createErrorToolResult(error instanceof Error ? error.message : String(error)),
			isError: true,
		};
	}
}

function emitToolExecutionUpdate(toolCall               , emit                )                 {
	return (partialResult) =>
		emit({
			type: "tool_execution_update",
			toolCallId: toolCall.id,
			toolName: toolCall.name,
			args: toolCall.arguments,
			partialResult,
		});
}

/** Options for {@link runToolCall}. */
                                                           
	                                       
	                                 
	                                                               
	                                   
	                                                        
	                      
	                     
	                          
 

/**
 * Run one tool call through the same steps as a model-issued call: argument preparation, schema
 * validation, `beforeToolCall`, execution, and `afterToolCall`. Emits no events and adds no
 * messages. Tools that call other tools use this so the hooks (for example permission checks)
 * apply to those calls too.
 *
 * Never rejects for tool failures: unknown tools, validation errors, blocked calls, and thrown
 * errors come back as `isError: true`.
 */
async function runToolCall(toolCall               , options                    )                                {
	const { assistantMessage, context, signal } = options;
	const preparation = await prepareToolCall(context, assistantMessage, toolCall, options, signal, options.tools);
	if (preparation.kind === "immediate") {
		return { toolCall, result: preparation.result, isError: preparation.isError };
	}
	const executed = await executePreparedToolCall(preparation, signal, options.onUpdate ?? (() => {}));
	return finalizeExecutedToolCall(context, assistantMessage, preparation, executed, options, signal);
}

async function executePreparedToolCall(
	prepared                  ,
	signal                         ,
	onUpdate                ,
)                                   {
	const updateEvents                  = [];
	let acceptingUpdates = true;
	const startedAt = performance.now();
	const elapsed = () => Math.round(performance.now() - startedAt);

	try {
		const result = await prepared.tool.execute(
			prepared.toolCall.id,
			prepared.args         ,
			signal,
			(partialResult) => {
				if (!acceptingUpdates) return;
				updateEvents.push(Promise.resolve(onUpdate(partialResult)));
			},
		);
		const durationMs = elapsed();
		acceptingUpdates = false;
		await Promise.all(updateEvents);
		return { result, isError: result.isError === true, durationMs };
	} catch (error) {
		const durationMs = elapsed();
		acceptingUpdates = false;
		await Promise.all(updateEvents);
		return {
			result: createErrorToolResult(error instanceof Error ? error.message : String(error)),
			isError: true,
			durationMs,
		};
	} finally {
		acceptingUpdates = false;
	}
}

async function finalizeExecutedToolCall(
	currentContext              ,
	assistantMessage                  ,
	prepared                  ,
	executed                         ,
	config               ,
	signal                         ,
)                                    {
	let result = executed.result;
	let isError = executed.isError;

	if (config.afterToolCall) {
		try {
			const afterResult = await config.afterToolCall(
				{
					assistantMessage,
					toolCall: prepared.toolCall,
					args: prepared.args,
					result,
					isError,
					context: currentContext,
				},
				signal,
			);
			if (afterResult) {
				// Structured content not replaced along with the content may no longer match it.
				const structuredContent =
					afterResult.structuredContent ?? (afterResult.content ? undefined : result.structuredContent);
				result = {
					...result,
					content: afterResult.content ?? result.content,
					details: afterResult.details ?? result.details,
					usage: afterResult.usage ?? result.usage,
					terminate: afterResult.terminate ?? result.terminate,
				};
				if (structuredContent === undefined) delete result.structuredContent;
				else result.structuredContent = structuredContent;
				isError = afterResult.isError ?? isError;
			}
		} catch (error) {
			result = createErrorToolResult(error instanceof Error ? error.message : String(error));
			isError = true;
		}
	}

	return {
		toolCall: prepared.toolCall,
		result,
		isError,
		durationMs: executed.durationMs,
	};
}

function createErrorToolResult(message        )                       {
	return {
		content: [{ type: "text", text: message }],
		details: {},
	};
}

async function emitToolExecutionEnd(finalized                          , emit                )                {
	await emit({
		type: "tool_execution_end",
		toolCallId: finalized.toolCall.id,
		toolName: finalized.toolCall.name,
		result: finalized.result,
		isError: finalized.isError,
		...(finalized.durationMs === undefined ? {} : { durationMs: finalized.durationMs }),
	});
}

function createToolResultMessage(finalized                          )                    {
	return {
		role: "toolResult",
		toolCallId: finalized.toolCall.id,
		toolName: finalized.toolCall.name,
		// Untyped tools (JS extensions) can return results without content; normalize
		// so the null never enters session history or provider payloads.
		content: finalized.result.content ?? [],
		details: finalized.result.details,
		usage: finalized.result.usage,
		isError: finalized.isError,
		...(finalized.durationMs === undefined ? {} : { durationMs: finalized.durationMs }),
		timestamp: Date.now(),
	};
}

async function emitToolResultMessage(toolResultMessage                   , emit                )                {
	await emit({ type: "message_start", message: toolResultMessage });
	await emit({ type: "message_end", message: toolResultMessage });
}


/***/ }),
/* 48 */
/***/ ((__unused_webpack_module, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   getDefaultStreamFn: () => (/* binding */ getDefaultStreamFn),
/* harmony export */   setDefaultStreamFn: () => (/* binding */ setDefaultStreamFn)
/* harmony export */ });
                                           

let defaultStreamFn                      ;

/**
 * Configure the fallback used by Agent and low-level loops when callers omit streamFn.
 *
 * Hosts that provide a default model runtime can install its stream function here
 * without making pi-agent-core depend on a provider catalog or compatibility layer.
 */
function setDefaultStreamFn(streamFn                      )       {
	defaultStreamFn = streamFn;
}

function getDefaultStreamFn()           {
	if (!defaultStreamFn) {
		throw new Error("No default stream function configured. Pass streamFn explicitly or call setDefaultStreamFn().");
	}
	return defaultStreamFn;
}


/***/ })
/******/ 	]);
/************************************************************************/
/******/ 	// The module cache
/******/ 	const __webpack_module_cache__ = {};
/******/ 	
/******/ 	// The require function
/******/ 	function __webpack_require__(moduleId) {
/******/ 		// Check if module is in cache
/******/ 		const cachedModule = __webpack_module_cache__[moduleId];
/******/ 		if (cachedModule !== undefined) {
/******/ 			return cachedModule.exports;
/******/ 		}
/******/ 		// Create a new module (and put it into the cache)
/******/ 		const module = __webpack_module_cache__[moduleId] = {
/******/ 			// no module.id needed
/******/ 			// no module.loaded needed
/******/ 			exports: {}
/******/ 		};
/******/ 	
/******/ 		// Execute the module function
/******/ 		__webpack_modules__[moduleId](module, module.exports, __webpack_require__);
/******/ 	
/******/ 		// Return the exports of the module
/******/ 		return module.exports;
/******/ 	}
/******/ 	
/************************************************************************/
/******/ 	/* webpack/runtime/define property getters */
/******/ 	(() => {
/******/ 		// define getter/value functions for harmony exports
/******/ 		__webpack_require__.d = (exports, definition) => {
/******/ 			if(Array.isArray(definition)) {
/******/ 				var i = 0;
/******/ 				while(i < definition.length) {
/******/ 					var key = definition[i++];
/******/ 					var binding = definition[i++];
/******/ 					if(!__webpack_require__.o(exports, key)) {
/******/ 						if(binding === 0) {
/******/ 							Object.defineProperty(exports, key, { enumerable: true, value: definition[i++] });
/******/ 						} else {
/******/ 							Object.defineProperty(exports, key, { enumerable: true, get: binding });
/******/ 						}
/******/ 					} else if(binding === 0) { i++; }
/******/ 				}
/******/ 			} else {
/******/ 				for(var key in definition) {
/******/ 					if(__webpack_require__.o(definition, key) && !__webpack_require__.o(exports, key)) {
/******/ 						Object.defineProperty(exports, key, { enumerable: true, get: definition[key] });
/******/ 					}
/******/ 				}
/******/ 			}
/******/ 		};
/******/ 	})();
/******/ 	
/******/ 	/* webpack/runtime/hasOwnProperty shorthand */
/******/ 	(() => {
/******/ 		__webpack_require__.o = (obj, prop) => (Object.prototype.hasOwnProperty.call(obj, prop))
/******/ 	})();
/******/ 	
/******/ 	/* webpack/runtime/make namespace object */
/******/ 	(() => {
/******/ 		// define __esModule on exports
/******/ 		__webpack_require__.r = (exports) => {
/******/ 			if(Symbol.toStringTag) {
/******/ 				Object.defineProperty(exports, Symbol.toStringTag, { value: 'Module' });
/******/ 			}
/******/ 			Object.defineProperty(exports, '__esModule', { value: true });
/******/ 		};
/******/ 	})();
/******/ 	
/************************************************************************/
let __webpack_exports__ = {};
// This entry needs to be wrapped in an IIFE because it needs to be isolated against other modules in the chunk.
(() => {
__webpack_require__.r(__webpack_exports__);
/* harmony import */ var _references_pi_mono_packages_agent_src_agent_ts__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(1);
/* harmony import */ var _references_pi_mono_packages_ai_src_utils_event_stream_ts__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(5);
// Test fixture only. Runs the genuine pinned upstream Agent, with no model or host transport.



globalThis.piFixtureStart = function(tool         ) {
  globalThis.piFixtureResult = undefined;
  let calls = 0;
  let recalls = 0;
  const events           = [];
  const agent = new _references_pi_mono_packages_agent_src_agent_ts__WEBPACK_IMPORTED_MODULE_0__.Agent({
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
      const s = new _references_pi_mono_packages_ai_src_utils_event_stream_ts__WEBPACK_IMPORTED_MODULE_1__.AssistantMessageEventStream();
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

})();

/******/ })()
;