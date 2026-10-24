package borg.trikeshed.narsese

/** Python API binding carried as process arguments; no generated executable or network service. */
object JevaProcess {
    val program = """
import hashlib, json, os, pathlib, sys
protocol = os.fdopen(os.dup(sys.stdout.fileno()), 'w', encoding='utf-8')
os.dup2(sys.stderr.fileno(), sys.stdout.fileno())
sys.stdout = sys.stderr
config = json.loads(sys.argv[1])
model = None
checkpoint = None
identity = None
runtime = None

def status():
    global checkpoint, identity, runtime
    if model is not None:
        return dict(available=True, error=None, model=config['model'], model_identity=identity,
                    checkpoint=checkpoint, device=config['device'], loaded=True, local_files_only=True, runtime=runtime)
    worker_sha256 = config.get('worker_sha256')
    if not isinstance(worker_sha256, str) or len(worker_sha256) != 64 or any(c not in '0123456789abcdef' for c in worker_sha256):
        raise ValueError('Jeva worker_sha256 must be a 64-character lowercase SHA256')
    if config.get('source'):
        source = pathlib.Path(config['source']).expanduser().resolve()
        if not source.is_dir():
            raise FileNotFoundError('Jeva source directory does not exist: ' + str(source))
        location = str(source / 'src' if (source / 'src').is_dir() else source)
        if location not in sys.path:
            sys.path.insert(0, location)
    import torch, transformers, peft, accelerate, yaml
    import jeva
    from jeva import Jeva
    source_file = pathlib.Path(jeva.__file__).resolve()
    source_files = sorted(source_file.parent.rglob('*.py')) if source_file.name == '__init__.py' else [source_file]
    source_digest = hashlib.sha256()
    for asset in source_files:
        source_digest.update(str(asset.relative_to(source_file.parent)).encode() + b'\x00')
        source_digest.update(hashlib.sha256(asset.read_bytes()).digest())
    runtime = dict(worker_sha256=worker_sha256, jeva_source_sha256=source_digest.hexdigest(),
                   python_implementation=sys.implementation.name, python_version=sys.version,
                   libraries={name: module.__version__ for name, module in
                              (('torch', torch), ('transformers', transformers), ('peft', peft), ('accelerate', accelerate))})
    if sys.implementation.name == 'graalpy':
        import __graalpython__
        runtime['graalpy'] = dict(native=__graalpython__.is_native, graalvm_version=__graalpython__.get_graalvm_version())
    runtime_fingerprint = hashlib.sha256(json.dumps(runtime, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    device = config['device']
    if device.startswith('cuda') and not torch.cuda.is_available():
        raise RuntimeError('Jeva CUDA device is unavailable')
    if device.startswith('mps') and not torch.backends.mps.is_available():
        raise RuntimeError('Jeva MPS device is unavailable')
    path = pathlib.Path(config['model']).expanduser()
    if not path.is_dir():
        if path.is_absolute() or config['model'].startswith('.'):
            raise FileNotFoundError('Jeva checkpoint directory does not exist: ' + str(path))
        from huggingface_hub import snapshot_download
        path = pathlib.Path(snapshot_download(repo_id=config['model'], revision=config.get('revision'), local_files_only=True))
    path = path.resolve()
    for name in ('config.yaml', 'head.pt'):
        if not (path / name).is_file():
            raise FileNotFoundError('Jeva checkpoint is incomplete: missing ' + name)
    with (path / 'config.yaml').open(encoding='utf-8') as handle:
        saved = yaml.safe_load(handle)
    cfg = saved['model']
    backbone = path / ('backbone_mlx' if device == 'mlx' else 'backbone_full' if (path / 'backbone_full').is_dir() else cfg.get('backbone', 'backbone'))
    if not backbone.is_dir() or not any(backbone.glob('*.safetensors')):
        raise FileNotFoundError('Jeva local backbone weights are missing: ' + str(backbone))
    if not (backbone / 'config.json').is_file():
        raise FileNotFoundError('Jeva backbone config.json is missing')
    for index in backbone.glob('*.safetensors.index.json'):
        for shard in set(json.loads(index.read_text())['weight_map'].values()):
            if not (backbone / shard).is_file():
                raise FileNotFoundError('Jeva backbone shard is missing: ' + shard)
    tokenizer = path / 'tokenizer' if (path / 'tokenizer').is_dir() else path / (cfg.get('tokenizer_path') or str(backbone))
    if not (tokenizer / 'tokenizer_config.json').is_file() or not any((tokenizer / name).is_file() for name in ('tokenizer.json', 'tokenizer.model', 'vocab.json')):
        raise FileNotFoundError('Jeva local tokenizer files are missing: ' + str(tokenizer))
    if device != 'mlx' and cfg.get('tune_mode', 'lora') == 'lora' and not (path / 'backbone_full').is_dir():
        adapter = path / 'backbone_adapter' / 'adapter_model.safetensors'
        if not adapter.is_file():
            raise FileNotFoundError('Jeva local LoRA weights are missing: ' + str(adapter))
        if not (adapter.parent / 'adapter_config.json').is_file():
            raise FileNotFoundError('Jeva local adapter_config.json is missing')
    if path.parent.name == 'snapshots':
        fingerprint = path.name
    else:
        digest = hashlib.sha256((path / 'config.yaml').read_bytes())
        digest.update((path / 'head.pt').read_bytes())
        for asset in sorted(path.rglob('*')):
            if asset.is_file() and asset.suffix in ('.safetensors', '.json', '.model', '.txt'):
                stat = asset.stat()
                digest.update((str(asset.relative_to(path)) + ':' + str(stat.st_size) + ':' + str(stat.st_mtime_ns)).encode())
        fingerprint = digest.hexdigest()
    checkpoint = str(path)
    identity = config['model'] + '@' + fingerprint + '+runtime:' + runtime_fingerprint
    return dict(available=True, error=None, model=config['model'], model_identity=identity,
                checkpoint=checkpoint, device=device, loaded=model is not None, local_files_only=True, runtime=runtime)

def configure(candidate):
    # Native tensor lifetimes and dense MPS attention need explicit bounds on GraalPy.
    if sys.implementation.name != 'graalpy' or not config['device'].startswith('mps'):
        return
    import gc
    import torch
    from transformers.integrations.sdpa_attention import sdpa_attention_forward, repeat_kv
    from transformers.modeling_utils import ALL_ATTENTION_FUNCTIONS
    from transformers.masking_utils import ALL_MASK_ATTENTION_FUNCTIONS, sdpa_mask

    ROW_LIMIT = 256
    RETAINED_BYTES = 3 << 30


    def attention_rows(module, query, key, value, attention_mask, dropout=0.0,
                       scaling=None, is_causal=None, position_bias=None, **kwargs):
        if query.shape[2] <= ROW_LIMIT:
            return sdpa_attention_forward(module, query, key, value, attention_mask,
                                          dropout=dropout, scaling=scaling, is_causal=is_causal,
                                          position_bias=position_bias, **kwargs)
        if dropout != 0.0 or module.training or position_bias is not None:
            raise ValueError('Row attention is qualified only for Qwen3.5 eval without dropout or position bias')
        if kwargs.get('output_attentions', False):
            raise ValueError('Row attention does not materialize attention weights')
        q_length, kv_length = query.shape[2], key.shape[2]
        if q_length != kv_length:
            raise ValueError('Row attention requires uncached equal-length Qwen3.5 prefill')
        # Repeat once, exactly as the MPS branch of Transformers SDPA does.
        groups = getattr(module, 'num_key_value_groups', 1)
        if groups > 1:
            key, value = repeat_kv(key, groups), repeat_kv(value, groups)
        causal = attention_mask is None and (getattr(module, 'is_causal', True) if is_causal is None else is_causal)
        if attention_mask is not None and attention_mask.ndim != 4:
            raise ValueError('Expected the Transformers four-dimensional SDPA mask')
        output = torch.empty((query.shape[0], q_length, query.shape[1], value.shape[-1]),
                             dtype=query.dtype, device=query.device)
        positions = torch.arange(kv_length, device=query.device) if causal else None
        collect = getattr(module, '_jeva_collect', None)
        for start in range(0, q_length, ROW_LIMIT):
            end = min(start + ROW_LIMIT, q_length)
            if attention_mask is not None:
                mask = attention_mask if attention_mask.shape[-2] == 1 else attention_mask[..., start:end, :]
            elif causal:
                mask = (positions.unsqueeze(0) <= torch.arange(start, end, device=query.device).unsqueeze(1))[None, None]
            else:
                mask = None
            block = torch.nn.functional.scaled_dot_product_attention(
                query[:, :, start:end], key, value, attn_mask=mask,
                dropout_p=0.0, scale=scaling, is_causal=False)
            output[:, start:end].copy_(block.transpose(1, 2))
            del block, mask
            if collect is not None:
                collect('attention_rows')
        return output, None


    def install(model):
        if sys.implementation.name != 'graalpy' or model.device.type != 'mps':
            raise ValueError('This compatibility module requires GraalPy and MPS')
        backbone = model.model.backbone.model
        if backbone.config.model_type != 'qwen3_5_text' or backbone.config.use_cache:
            raise ValueError('This compatibility module requires uncached Qwen3.5 text inference')
        torch.mps.synchronize()
        gc.collect()
        baseline = torch.mps.current_allocated_memory()
        def collect(boundary, force=False):
            allocated = torch.mps.current_allocated_memory()
            if not force and allocated <= baseline + RETAINED_BYTES:
                return
            torch.mps.synchronize()
            gc.collect()
            remaining = torch.mps.current_allocated_memory()
            driver = torch.mps.driver_allocated_memory()
            if driver - remaining > 2 << 30:
                torch.mps.empty_cache()
        handles = []
        def before(module, args):
            if args and args[0].shape[1] >= 4096 and torch.mps.current_allocated_memory() > baseline + (512 << 20):
                collect('long_decoder_input', force=True)
        def after(module, args, output):
            collect(type(module).__name__)
        layers = 0
        for module in backbone.modules():
            if type(module).__name__ == 'Qwen3_5DecoderLayer':
                layers += 1
                handles.append(module.register_forward_pre_hook(before))
                handles.append(module.register_forward_hook(after))
            elif type(module).__name__ == 'Qwen3_5Attention':
                module._jeva_collect = collect
        assert layers == 32, 'Unexpected decoder layer count'
        ALL_ATTENTION_FUNCTIONS.register('jeva_mps_rows', attention_rows)
        ALL_MASK_ATTENTION_FUNCTIONS.register('jeva_mps_rows', sdpa_mask)
        backbone.set_attn_implementation('jeva_mps_rows')
        assert backbone.config._attn_implementation == 'jeva_mps_rows'

    install(candidate)

def infer(request):
    global model
    if checkpoint is None:
        status()
    if model is None:
        from jeva import Jeva
        if config['device'] == 'mlx':
            candidate = Jeva.from_mlx(checkpoint, max_state_length=config['max_state_length'],
                max_option_length=config['max_option_length'], truncation='error')
        else:
            candidate = Jeva.from_pretrained(checkpoint, device=config['device'], local_files_only=True,
                trust_remote_code=False, max_state_length=config['max_state_length'],
                max_option_length=config['max_option_length'], truncation='error')
        try:
            configure(candidate)
        except Exception:
            del candidate
            import gc
            gc.collect()
            raise
        model = candidate
    answers = {}
    usage = {}
    for name, question in request['questions'].items():
        answer = model.predict(state=request['state'], question=question['question'],
            decision_type=question['decision_type'], options=question['options'],
            allow_null=question['allow_null'], positive_index=question['positive_index']).to_dict()
        answers[name] = answer
        for key, value in answer.get('usage', {}).items():
            usage[key] = usage.get(key, 0) + value
    return dict(model=identity, answers=answers, usage=usage)

for line in sys.stdin:
    try:
        request = json.loads(line)
        if request['op'] == 'status':
            result = status()
        elif request['op'] == 'infer':
            result = infer(request)
        else:
            raise ValueError('Unknown Jeva operation')
    except Exception as failure:
        result = dict(available=False, error=type(failure).__name__ + ': ' + str(failure))
    protocol.write(json.dumps(result, ensure_ascii=False, allow_nan=False) + '\n')
    protocol.flush()
""".trimIndent()
}
