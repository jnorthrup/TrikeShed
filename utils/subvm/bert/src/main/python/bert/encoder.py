"""Last-four-layer target-word means; absent or truncated targets remain unencoded."""

import json
import re


def target(t):
    text, lemma = t['sentence'], t['lemma']
    if 'start' in t:
        raw = text.encode('utf-16-le')
        start, end = t['start'], t['end']
        if not 0 <= start < end <= len(raw)//2: raise ValueError('Invalid target offsets')
        a = len(raw[:start*2].decode('utf-16-le'))
        b = len(raw[:end*2].decode('utf-16-le'))
        return (a,b), 'Explicit'
    matches = list(re.finditer(r'(?i)(?<![A-Za-z])' + re.escape(lemma) + r'(?![A-Za-z])', text))
    if matches: return (matches[0].span(), 'Exact') if len(matches) == 1 else (None, 'Ambiguous')
    stem = lemma.lower()[:max(4, len(lemma)-2)]
    matches = [w for w in re.finditer(r'[A-Za-z]+', text) if w.group().lower().startswith(stem)]
    if matches: return (matches[0].span(), 'Stem') if len(matches) == 1 else (None, 'Ambiguous')
    return None, 'Missing'


class Encoder:
    def __init__(self, weights, requested='auto'):
        import torch
        from transformers import AutoTokenizer, AutoModel
        torch.set_num_threads(2)
        self.torch = torch
        self.device = ('mps' if torch.backends.mps.is_available() else 'cpu') if requested == 'auto' else requested
        self.tokenizer = AutoTokenizer.from_pretrained(weights, local_files_only=True, use_fast=True, trust_remote_code=False)
        self.model = AutoModel.from_pretrained(weights, local_files_only=True, use_safetensors=True,
                                             trust_remote_code=False, output_hidden_states=True).to(self.device).eval()
        if self.device == 'mps': self.model = self.model.half()

    @property
    def dimensions(self):
        return self.model.config.hidden_size

    def encode(self, tokens):
        torch = self.torch
        tokenizer = self.tokenizer
        model = self.model
        device = self.device
        if not tokens: return []
        targets = [target(t) for t in tokens]
        sentences = list(dict.fromkeys(t['sentence'] for t, (span, _) in zip(tokens, targets) if span))
        rows = {s:i for i,s in enumerate(sentences)}
        if sentences:
            batch = tokenizer(sentences, return_offsets_mapping=True, truncation=True,
                              max_length=512, padding=True, return_tensors='pt')
            offsets = batch.pop('offset_mapping')
            with torch.inference_mode():
                hidden = model(**{k:v.to(device) for k,v in batch.items()}).hidden_states
                vectors = torch.stack(hidden[-4:]).float().mean(0).cpu()
        result = []
        for t, (span, method) in zip(tokens, targets):
            value = None
            if span:
                row = rows[t['sentence']]
                off = offsets[row]
                hit = (off[:,1] > span[0]) & (off[:,0] < span[1]) & (off[:,1] > off[:,0])
                selected = off[hit]
                if len(selected) and int(selected[0,0]) <= span[0] and int(selected[-1,1]) >= span[1]:
                    value = vectors[row][hit].mean(0).tolist()
                else: method = 'Truncated'
            result.append({'span':method, 'values':value})
        return result


def serve(encoder, input_stream, output_stream):
    for line in input_stream:
        request = json.loads(line)
        try:
            tokens = request['tokens']
            if len(tokens) > 16: raise ValueError('BERT batch exceeds 16 targets')
            result = {'id':request['id'], 'dimensions':encoder.dimensions, 'vectors':encoder.encode(tokens)}
        except Exception as e:
            result = {'id':request['id'], 'error':type(e).__name__ + ': ' + str(e)}
        print(json.dumps(result, allow_nan=False, separators=(',',':')), file=output_stream, flush=True)


def main(args, input_stream, output_stream):
    encoder = Encoder(args[0], args[1])
    serve(encoder, input_stream, output_stream)
