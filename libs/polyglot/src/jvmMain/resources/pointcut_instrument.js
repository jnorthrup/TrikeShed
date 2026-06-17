// pointcut_instrument.js — JS-side mirror of pointcut_instrument.py.
//
// Auto-installed by GraalPointcutHarness for every JS Context. Provides:
//   - pointcut_instrument.wrapHostObject(host, className) → Proxy
//   - pointcut_instrument.instrument_class(cls) — no-op for sealed host classes
//   - pointcut_instrument.set_emitter(emitter) — host calls this

// IIFE: assign the module to the global object so callers can do
// `pointcut_instrument.wrapHostObject(...)` after the harness installs this.
(function () {
    'use strict';

    var _emitter = null;
    var _seq = 0;

    function _nextSeq() {
        _seq += 1;
        return _seq;
    }

    function set_emitter(emitter) {
        _emitter = emitter;
    }

    function _emit(phase, isStatic, isWrite, className, fieldName, location, seq) {
        if (_emitter !== null) {
            _emitter.emitFieldAccess(phase, isStatic, isWrite, className, fieldName, location, seq);
        }
    }

    /**
     * Wrap a host object so that all field reads/writes route through the
     * pointcut emitter. Returns a JS Proxy that delegates everything else
     * (method calls) to the original host object.
     */
    function wrapHostObject(host, className) {
        var classNameStr = className ||
            (host && host.constructor && host.constructor.name) ||
            'UnknownHost';
        return new Proxy(host, {
            get: function (target, prop, receiver) {
                if (typeof prop === 'symbol' || (typeof prop === 'string' && prop.charAt(0) === '_')) {
                    return Reflect.get(target, prop, receiver);
                }
                var seq = _nextSeq();
                var isStatic = (typeof target === 'function');
                _emit(0, isStatic, false, classNameStr, String(prop), classNameStr + '.__getattribute__', seq);
                var v;
                try {
                    v = Reflect.get(target, prop, receiver);
                } catch (e) {
                    _emit(1, isStatic, false, classNameStr, String(prop), classNameStr + '.__getattribute__', seq);
                    throw e;
                }
                _emit(1, isStatic, false, classNameStr, String(prop), classNameStr + '.__getattribute__', seq);
                return v;
            },
            set: function (target, prop, value, receiver) {
                if (typeof prop === 'symbol' || (typeof prop === 'string' && prop.charAt(0) === '_')) {
                    return Reflect.set(target, prop, value, receiver);
                }
                var seq = _nextSeq();
                var isStatic = (typeof target === 'function');
                _emit(0, isStatic, true, classNameStr, String(prop), classNameStr + '.__setattr__', seq);
                var ok;
                try {
                    ok = Reflect.set(target, prop, value, receiver);
                } catch (e) {
                    _emit(1, isStatic, true, classNameStr, String(prop), classNameStr + '.__setattr__', seq);
                    throw e;
                }
                _emit(1, isStatic, true, classNameStr, String(prop), classNameStr + '.__setattr__', seq);
                return ok;
            }
        });
    }

    /**
     * Mark a JS class as instrumented. For host classes this is a no-op
     * marker (host classes are sealed). For pure-JS classes, the caller
     * should use wrapHostObject on instances instead.
     */
    function instrument_class(cls) {
        if (!cls) return cls;
        if (cls.__pointcut_instrumented) return cls;
        cls.__pointcut_instrumented = true;
        return cls;
    }

    // GraalVM JS uses a global object: in script-eval mode, `this` is the
    // global object, but we also use `globalThis` for clarity and to be
    // engine-agnostic. Assignment via globalThis always lands in the
    // global lexical scope.
    globalThis.pointcut_instrument = {
        set_emitter: set_emitter,
        wrapHostObject: wrapHostObject,
        instrument_class: instrument_class
    };
})();
