"""Safe extraction and recorded-data audit only: no experiment code/JVM/parser executes."""
import argparse, hashlib, json, pathlib, re, struct, tarfile, zipfile

if not __debug__:
    raise RuntimeError('Run recorded replay without Python optimization flags')

package = pathlib.Path(__file__).resolve().parent
def read(p): return json.loads(pathlib.Path(p).read_text())
def sha_bytes(b): return hashlib.sha256(b).hexdigest()
def sha_file(p):
    h = hashlib.sha256()
    with pathlib.Path(p).open('rb') as f:
        for block in iter(lambda: f.read(1024*1024), b''): h.update(block)
    return h.hexdigest()
def pin(p, expected):
    p = pathlib.Path(p)
    assert p.stat().st_size == expected['bytes'], str(p)
    assert sha_file(p) == expected['sha256'], str(p)

def member_path(name):
    assert isinstance(name, str) and name and len(name) <= 512
    p = pathlib.PurePosixPath(name)
    assert not p.is_absolute() and str(p) == name and '\\' not in name
    assert all(part not in ('', '.', '..') for part in name.split('/'))
    assert all(ord(c) >= 32 and ord(c) != 127 for c in name)
    return p

def safe_extract(archive, destination, manifest):
    assert not destination.exists() and not destination.is_symlink(), 'Choose a fresh extraction directory'
    included = manifest['included']
    assert 0 < len(included) <= 200
    expected = {}
    for item in included:
        member_path(item['file'])
        assert item['file'] not in expected
        assert 0 <= item['bytes'] <= 4*1024*1024
        assert re.fullmatch('[0-9a-f]{64}', item['sha256'])
        expected[item['file']] = item
    assert sum(item['bytes'] for item in included) <= 16*1024*1024
    # Validate every header before creating the destination. No extractall, links,
    # devices, pipes, duplicate paths, unlisted entries or archive permissions.
    with tarfile.open(archive, 'r:gz') as tar:
        members = []; seen = set()
        for member in tar:
            assert len(members) < len(expected)
            member_path(member.name)
            assert member.isfile() and member.type in (tarfile.REGTYPE, tarfile.AREGTYPE)
            assert not member.linkname and not member.pax_headers
            assert member.name not in seen and member.name in expected
            seen.add(member.name)
            assert member.size == expected[member.name]['bytes']
            members.append(member)
        assert seen == set(expected)
        destination.mkdir(parents=True)
        for member in members:
            target = destination.joinpath(*member_path(member.name).parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            assert not target.exists() and not target.is_symlink()
            stream = tar.extractfile(member); assert stream is not None
            data = stream.read(member.size+1)
            assert len(data) == member.size and sha_bytes(data) == expected[member.name]['sha256']
            with target.open('xb') as f: f.write(data)
            target.chmod(0o644)
    return len(expected)

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--extract', type=pathlib.Path, required=True, help='New empty/nonexistent destination; existing paths are refused')
parser.add_argument('--verify-external', action='store_true', help='Optionally rehash original workspace SDK/context/records and grammar ZIP resource; never executes them')
args = parser.parse_args()
outer = read(package/'SHA256.json')
assert outer['schema'] == 'english-pcfg-public-package/1'
assert {x['file'] for x in outer['files']} == {'README.md','replay-recorded.py','records-manifest.json','records.tar.gz'}
for item in outer['files']:
    assert pathlib.PurePosixPath(item['file']).name == item['file']
    pin(package/item['file'], item)
manifest = read(package/'records-manifest.json')
assert manifest['schema'] == 'english-pcfg-recorded-files/1'
destination = args.extract.absolute()
included_count = safe_extract(package/'records.tar.gz', destination, manifest)
root = destination/'observer'
attempt = root/'attempts/attempt001'
executed = root/'executed-source-20261105T052735Z'

final = read(root/'final-record-manifest.json')
omitted = manifest['omittedOriginalFiles']
assert [x['file'] for x in omitted] == ['observer/attempts/attempt001/classes/PcfgObserver.class']
original_omitted = {x['file'].removeprefix('observer/') for x in omitted}
for item in final['records']:
    if item['file'] not in original_omitted: pin(root/item['file'], item)
    else: assert item['sha256'] == read(attempt/'compiled-classes.json')[0]['sha256']

protocol = read(root/'protocol-manifest.json')
for row in protocol['files']:
    pin(root/row['file'], row)
    pin(executed/row['file'], row)
frozen = read(root/'frozen-inputs.json')
all_pins = [frozen['sdkManifest'], *frozen['sdkClasspath'], *frozen['tools'],
            *frozen['snapshotContextOnlyNotRuntimeClasspath'], *frozen['snapshotMetadata']]
if args.verify_external:
    for row in all_pins: pin(row['path'], row)
for record in frozen['records']:
    assert record['original']['sha256'] == record['materialized']['sha256']
    assert record['original']['bytes'] == record['materialized']['bytes']
    relative = record['materialized']['path'].split('/english-pcfg-comparison-observer/',1)[1]
    pin(root/relative, record['materialized'])
    if args.verify_external:
        for row in record.values(): pin(row['path'], row)
assert len(frozen['sdkClasspath']) == 28
assert len(frozen['snapshotContextOnlyNotRuntimeClasspath']) == 120
assert read(root/'frozen-inputs.json') == read(executed/'frozen-inputs.json')
sdk_rows = [line.split('\t') for line in (destination/'sdk/MANIFEST.tsv').read_text().splitlines() if line.split('\t')[0].endswith('.jar')]
assert len(sdk_rows) == 28
pin(destination/'sdk/MANIFEST.tsv', frozen['sdkManifest'])
sdk_by_path = {row['path']:row for row in frozen['sdkClasspath']}
for name, size, digest in sdk_rows:
    recorded = [x for x in frozen['sdkClasspath'] if pathlib.PurePosixPath(x['path']).name == name]
    assert len(recorded) == 1 and recorded[0]['bytes'] == int(size) and recorded[0]['sha256'] == digest
assert len(read(attempt/'compiled-classes.json')) == 1

def decode_cbor(data):
    pos = 0
    def take(n):
        nonlocal pos
        assert pos+n <= len(data)
        value = data[pos:pos+n]; pos += n
        return value
    def one(depth=0):
        assert depth < 48
        head = take(1)[0]; kind = head >> 5; extra = head & 31
        if kind == 7:
            if extra in (20,21): return extra == 21
            if extra in (22,23): return None
            if extra in (25,26,27):
                return struct.unpack({25:'>e',26:'>f',27:'>d'}[extra],take({25:2,26:4,27:8}[extra]))[0]
            raise AssertionError('Unsupported CBOR simple value')
        n = extra if extra < 24 else int.from_bytes(take({24:1,25:2,26:4,27:8}[extra]),'big')
        if kind == 0: return n
        if kind == 1: return -1-n
        if kind == 2: return take(n)
        if kind == 3: return take(n).decode('utf-8')
        if kind == 4: return [one(depth+1) for _ in range(n)]
        if kind == 5:
            result = {}
            for _ in range(n):
                key = one(depth+1); assert key not in result
                result[key] = one(depth+1)
            return result
        raise AssertionError('Unexpected CBOR kind')
    result = one(); assert pos == len(data)
    return result

def penn(text):
    tokens = re.findall(r'\(|\)|[^\s()]+', text); pos = 0
    def node():
        nonlocal pos
        assert tokens[pos] == '('; pos += 1
        label = tokens[pos]; pos += 1; children = []
        while tokens[pos] != ')':
            if tokens[pos] == '(': children.append(node())
            else: children.append(tokens[pos]); pos += 1
        pos += 1
        return label, children
    tree = node(); assert pos == len(tokens)
    return tree

summary = read(attempt/'comparison-summary.json')
results = []
for case in ('G07','G13'):
    path = root/'inputs'/case
    baseline = read(path/'response.json')
    source_bytes = (path/'source.txt').read_bytes()
    source = source_bytes.decode('utf-8')
    assert source.encode('utf-8') == source_bytes
    assert 'sha256:'+sha_bytes(source_bytes) == baseline['originalCid'] == baseline['extractedTextCid']
    cbor = (path/'cas-readbacks'/(baseline['receiptCid'][7:]+'.cbor')).read_bytes()
    assert 'sha256:'+sha_bytes(cbor) == baseline['receiptCid']
    assert decode_cbor(cbor) == {k:v for k,v in baseline.items() if k != 'receiptCid'}
    r = read(attempt/case/'observation.json')
    assert r['status'] == 'OBSERVED' and r['stage'] == 'complete'
    assert r['source']['text'] == source
    assert r['source']['baselineInspectionCid'] == baseline['receiptCid']
    utf16 = source.encode('utf-16-le')
    assert r['source']['sourceUtf8Bytes'] == len(source_bytes)
    assert r['source']['sourceUtf16Units'] == len(utf16)//2
    sentence = baseline['sentences'][0]
    assert len(baseline['sentences']) == 1
    assert sentence['begin'] == 0 and sentence['end'] == len(utf16)//2 and sentence['quote'] == source
    assert len(r['tokens']) == len(r['pos']) == len(sentence['tokens'])
    assert all(n['copy'] == 0 for n in sentence['nodes'])
    for actual, tag, old in zip(r['tokens'], r['pos'], sentence['tokens']):
        for field in ('index','begin','end'): assert actual[field] == tag[field] == old[field]
        word = utf16[2*old['begin']:2*old['end']].decode('utf-16-le')
        assert actual['sourceWord'] == actual['tokenizerOriginalText'] == tag['sourceWord'] == old['word'] == word
        assert actual['inputPos'] is None and actual['baselineCopy'] == 0
        assert actual['baselineNodeId'] == tag['baselineNodeId'] == str(old['index'])+':0'
        assert actual['baselinePos'] == tag['baselinePos'] == old['pos']
        assert tag['posChanged'] == (tag['pcfgPos'] != old['pos'])
    assert (attempt/case/'tree.penn.txt').read_text().strip() == r['tree']
    observed_pos, constituents = [], []
    def walk(tree, start):
        label, children = tree; end = start
        if len(children) == 1 and isinstance(children[0], str):
            observed_pos.append((label, children[0])); end += 1
        else:
            for child in children: assert not isinstance(child,str); end = walk(child,end)
        lo, hi = r['tokens'][start]['begin'], r['tokens'][end-1]['end']
        constituents.append({'label':label,'firstTokenIndex':start+1,'endTokenIndexExclusive':end+1,
            'begin':lo,'end':hi,'quote':utf16[2*lo:2*hi].decode('utf-16-le'),'meaning':'grammar-constituent-observation'})
        return end
    assert walk(penn(r['tree']),0) == len(r['tokens'])
    assert observed_pos == [(x['pcfgPos'],x['parserWord']) for x in r['pos']]
    assert [word for _,word in observed_pos] == [x['tokenizerWord'] for x in r['tokens']]
    assert constituents == r['constituents']
    grammar = r['grammar']; jar = grammar['url'].removeprefix('jar:file:').split('!/',1)[0]
    assert grammar['bytes'] == 3317343 and grammar['sha256'] == '034735fc9fcf3478b4ea3dd0bbc65106d595ece384375be37ffe48dbce4a0c33'
    assert jar in sdk_by_path and jar.endswith('/stanford-corenlp-4.6.0-models.jar')
    assert sdk_by_path[jar]['sha256'] == 'b3ff73020ea5b764bae94a3f4ff727e913df5c0ce998bf4506e9bb569a8b2e60'
    if args.verify_external:
        with zipfile.ZipFile(jar) as z: grammar_bytes = z.read(grammar['resource'])
        assert len(grammar_bytes) == grammar['bytes'] and sha_bytes(grammar_bytes) == grammar['sha256']
    for origin in r['classOrigins']:
        if origin['sha256'] is not None:
            origin_path = origin['codeSource'].removeprefix('file:')
            assert sdk_by_path[origin_path]['sha256'] == origin['sha256']
            if args.verify_external: assert sha_file(origin_path) == origin['sha256']
            assert origin['packageVersion'] == '4.6.0'
    assert r['applicationMainJarLoaded'] is False and r['newInspectionReceipt'] is False
    argv = read(attempt/case/'actual-process/argv.json')
    cp = argv[argv.index('-cp')+1].split(':')
    assert cp[0] == '/workspace/library-files/english-pcfg-comparison-observer/attempts/attempt001/classes'
    assert cp[1:] == [x['path'] for x in frozen['sdkClasspath']]
    assert not any(x.endswith('/main.jar') for x in cp)
    assert r['jvm']['airgap'] == '1' and r['jvm']['processors'] == 2
    assert r['jvm']['maxMemory'] == 1536*1024*1024
    assert r['parserConfiguration']['inputPos'] == 'absent'
    assert r['parserConfiguration']['observerRepairOrRetry'] is False
    for where in (case,case+'-netns'):
        process_dir = attempt/where
        if where == case: process_dir /= 'actual-process'
        process = read(process_dir/'process.json'); assert process['exit'] == 0
        for stream in ('stdout','stderr'): assert sha_file(process_dir/(stream+'.txt')) == process[stream+'Sha256']
    before = read(attempt/case/'network-before.json'); after = read(attempt/case/'network-after.json')
    assert before == after and [x['ifname'] for x in before['addresses']] == ['lo']
    assert all(x.get('dev') == 'lo' for x in before['routes'])
    assert all(x['local'] in ('127.0.0.1','::1') for n in before['addresses'] for x in n.get('addr_info',[]))
    rows = next(x for x in summary['cases'] if x['case'] == case)
    differences = [x for x in r['pos'] if x['posChanged']]
    assert rows['posDifferences'] == differences
    assert rows['observationSha256'] == sha_file(attempt/case/'observation.json')
    assert rows['baselineDependenciesUnchanged'] == sentence['dependencies']
    results.append({'case':case,'tokens':len(r['tokens']),'constituents':len(constituents),
        'posDifferenceIndices':[x['index'] for x in differences],'sourceAndCborHash':'PASS',
        'tokenUtf16AndIndependentTreePosConstituentAlignment':'PASS','recordedGrammarAndRuntimeClasspathPins':'PASS',
        'recordedLoopbackNetworkAndChildExits':'PASS'})

assert summary['newProductionInspectionReceipts'] == 0 and summary['accuracyRate'] is None
g07 = read(attempt/'G07/observation.json'); g13 = read(attempt/'G13/observation.json')
assert [(x['index'],x['pcfgPos']) for x in g07['pos'] if x['index'] in (9,10)] == [(9,'NN'),(10,'NNS')]
assert '(NP (NN Trustee) (NNS consents))' in g07['tree']
assert [(x['index'],x['pcfgPos'],x['baselinePos']) for x in g13['pos'] if x['index'] in (4,8)] == [(4,"''",'``'),(8,'``','``')]
assert '(PP (`` ") (IN without)' in g13['tree']
preflight = read(root/'attempts/attempt000-preflight/result.json')
assert preflight['exit'] == 1 and preflight['inheritedAirgap'] is None
assert preflight['compilerStarted'] is False and preflight['jvmStarted'] is False
assert 'AIRGAP must already be1' in (root/'attempts/attempt000-preflight/tool-output.txt').read_text()
for phase in ('compile','G07','G13'):
    before = read(attempt/phase/'network-before.json')
    after = read(attempt/phase/'network-after.json')
    assert before == after and [x['ifname'] for x in before['addresses']] == ['lo']
    assert all(x.get('dev') == 'lo' for x in before['routes'])
    wrapper = read(attempt/(phase+'-netns')/'argv.json')
    assert all(x in wrapper for x in ('--user','--map-root-user','--net','--fork','--kill-child=SIGKILL'))
    assert before['namespace'] != wrapper[wrapper.index('--parent-netns')+1]
    for process_dir in (attempt/phase/'actual-process', attempt/(phase+'-netns')):
        process = read(process_dir/'process.json'); assert process['exit'] == 0
        for stream in ('stdout','stderr'):
            assert sha_file(process_dir/(stream+'.txt')) == process[stream+'Sha256']
compiler = read(attempt/'compile/actual-process/argv.json')
assert '-J-Xmx768m' in compiler and '-proc:none' in compiler
assert compiler[compiler.index('-cp')+1].split(':') == [x['path'] for x in frozen['sdkClasspath']]
closure = read(attempt/'closure.json')
assert closure['compilerAndTwoParsersExit'] == [0,0,0]
assert closure['visibleProcessesRetainingExperimentNamespaces'] == []
assert closure['visibleObserverCompilerOrJavaProcesses'] == []
assert closure['inaccessibleProcEntries'] == 508
result = {'result':'RECORDED_REPLAY_PASS','scope':'Bundled recorded hash/source-CBOR/UTF16/Penn-tree/closure-scope audit only; no compiler, JVM, parser, model download or production execution',
    'cases':results,'archiveFilesVerified':included_count,
    'initialAirgapRefusalPreservedWithoutCompilerOrJvm':True,
    'externalDependenciesMode':'rehash-original-workspace' if args.verify_external else 'recorded-metadata-only',
    'sdkJarsRehashed':28 if args.verify_external else 0,'contextJarsRehashedNotRuntimeClasspath':120 if args.verify_external else 0,
    'protocolFilesRehashedAtBothLocations':len(protocol['files']),
    'honestNegativeConclusion':'G07 coordination ambiguity not corrected; G13 odd quote POS/PP placement retained; neither establishes legal scope or accuracy uplift',
    'closureLimit':'Recorded child exits and visible-process closure only; 508 inaccessible /proc entries preclude whole-host-zero claim',
    'runtimeOriginLimit':'External observer plus 28 SDK jars; application main e235 remains contextual metadata only',
    'newParserRunsByReviewer':0,'productOrOriginalRecordChanges':0}
(destination/'recorded-replay-result.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result))
