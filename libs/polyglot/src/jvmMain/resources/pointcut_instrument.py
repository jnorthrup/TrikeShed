import sys
import types

print("[POINT CUT] Module loaded", file=sys.stderr)
sys.stderr.flush()

# Global emitter reference (bound by host)
_emitter = None
_instrumented_classes = set()

def _set_emitter(emitter):
    '''Called by host to register the pointcut emitter.'''
    global _emitter
    _emitter = emitter

def _emit(phase, is_static, is_write, class_name, field_name, location, seq):
    '''Emit a pointcut via the host-bound emitter.'''
    if _emitter is not None:
        _emitter.emitFieldAccess(phase, is_static, is_write, class_name, field_name, location, seq)

def _make_getattribute(class_name, original_getattribute=None):
    '''Create a __getattribute__ that emits L_GET/P_GET pointcuts for ALL attribute access.'''
    def instrumented_getattribute(self, name):
        # Skip private/dunder attributes to avoid recursion
        if name.startswith('_'):
            if original_getattribute:
                return original_getattribute(self, name)
            return object.__getattribute__(self, name)
        
        # Determine if static (class attribute) or instance
        is_static = isinstance(self, type)
        
        # BEFORE phase
        seq = _get_next_seq()
        _emit(0, is_static, False, class_name, name, class_name + '.__getattribute__', seq)
        
        try:
            if original_getattribute:
                result = original_getattribute(self, name)
            else:
                result = object.__getattribute__(self, name)
        except AttributeError:
            # Still emit AFTER on exception
            _emit(1, is_static, False, class_name, name, class_name + '.__getattribute__', seq)
            raise
        
        # AFTER phase
        _emit(1, is_static, False, class_name, name, class_name + '.__getattribute__', seq)
        return result
    return instrumented_getattribute

def _make_setattr(class_name, original_setattr=None):
    '''Create a __setattr__ that emits L_SET/P_SET pointcuts.'''
    def instrumented_setattr(self, name, value):
        # Skip private/dunder attributes
        if name.startswith('_'):
            if original_setattr:
                return original_setattr(self, name, value)
            object.__setattr__(self, name, value)
            return
        
        is_static = isinstance(self, type)
        
        # BEFORE phase
        seq = _get_next_seq()
        _emit(0, is_static, True, class_name, name, class_name + '.__setattr__', seq)
        
        try:
            if original_setattr:
                original_setattr(self, name, value)
            else:
                object.__setattr__(self, name, value)
        except Exception:
            _emit(1, is_static, True, class_name, name, class_name + '.__setattr__', seq)
            raise
        
        # AFTER phase
        _emit(1, is_static, True, class_name, name, class_name + '.__setattr__', seq)
    return instrumented_setattr

_seq_counter = 0
def _get_next_seq():
    global _seq_counter
    _seq_counter += 1
    return _seq_counter

def instrument_class(cls, class_name=None):
    '''Instrument a class to emit pointcuts on attribute access.'''
    global _instrumented_classes
    
    if class_name is None:
        class_name = cls.__name__
    
    # Skip if already instrumented
    if cls in _instrumented_classes:
        return cls
    
    print("[POINT CUT] Instrumenting class: " + class_name)
    
    # Save original methods if they exist
    original_getattribute = getattr(cls, '__getattribute__', None)
    original_setattr = getattr(cls, '__setattr__', None)
    
    # Install instrumented versions
    cls.__getattribute__ = _make_getattribute(class_name, original_getattribute)
    cls.__setattr__ = _make_setattr(class_name, original_setattr)
    
    # Also instrument __delattr__ if present
    original_delattr = getattr(cls, '__delattr__', None)
    if original_delattr:
        def instrumented_delattr(self, name):
            if name.startswith('_'):
                return original_delattr(self, name)
            is_static = isinstance(self, type)
            seq = _get_next_seq()
            _emit(0, is_static, True, class_name, name, class_name + '.__delattr__', seq)
            try:
                original_delattr(self, name)
            except Exception:
                _emit(1, is_static, True, class_name, name, class_name + '.__delattr__', seq)
                raise
            _emit(1, is_static, True, class_name, name, class_name + '.__delattr__', seq)
        cls.__delattr__ = instrumented_delattr
    
    _instrumented_classes.add(cls)
    return cls

def instrument_module(mod):
    '''Instrument all classes in a module.'''
    for name in dir(mod):
        obj = getattr(mod, name)
        if isinstance(obj, type):
            instrument_class(obj, name)
    return mod

def auto_instrument(target):
    '''Automatically instrument a class or module.'''
    if isinstance(target, type):
        return instrument_class(target)
    elif isinstance(target, types.ModuleType):
        return instrument_module(target)
    else:
        raise TypeError('Cannot auto-instrument ' + str(type(target)))

# Export functions
def set_emitter(emitter):
    '''Host calls this to bind the emitter.'''
    _set_emitter(emitter)

# Make auto_instrument available as 'pointcut_instrument' module
pointcut_instrument = sys.modules[__name__]
pointcut_instrument.instrument_class = instrument_class
pointcut_instrument.instrument_module = instrument_module
pointcut_instrument.auto_instrument = auto_instrument
pointcut_instrument.set_emitter = set_emitter

# Register as importable module
sys.modules['pointcut_instrument'] = pointcut_instrument