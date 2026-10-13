"""Keep source control in GraalPy; the bound encoder owns numerical inference."""
import json


class Encoder:
    def __init__(self, weights, requested='auto'):
        if requested != 'auto':
            raise ValueError('The host owns BERT device selection')
        self.model = host.call('bert_model')
        if weights is not None and weights != self.model:
            raise ValueError('BERT weights differ from the host-bound model')

    def encode(self, tokens):
        if len(tokens) > 16:
            raise ValueError('BERT batch exceeds 16 targets')
        return json.loads(host.call('bert_encode', tokens))
