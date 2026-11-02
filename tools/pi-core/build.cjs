// Explicit local inputs only: node tools/pi-core/build.cjs PINNED_CHECKOUT WEBPACK_MODULE
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const { execFileSync } = require('node:child_process');
const [checkout, webpackModule] = process.argv.slice(2);
if (!checkout || !webpackModule) throw Error('usage: node tools/pi-core/build.cjs PINNED_CHECKOUT WEBPACK_MODULE');
const upstream = fs.realpathSync(checkout);
const webpack = require(fs.realpathSync(webpackModule));
const repo = path.resolve(__dirname, '../..');
const resources = path.join(repo, 'src/jvmMain/resources/pi-core');
const manifestPath = path.join(resources, 'manifest.json');
const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
const git = (...args) => execFileSync('git', ['-C', upstream, ...args], { encoding: 'utf8' }).trim();
if (git('rev-parse', 'HEAD') !== manifest.revision) throw Error('upstream revision differs from pinned manifest');
if (git('status', '--porcelain', '--untracked-files=all', '--', 'packages')) throw Error('upstream packages must be clean, including untracked inputs');
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'graal-pi-bundle-'));
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
try {
  fs.mkdirSync(path.join(temp, 'upstream'));
  fs.cpSync(path.join(upstream, 'packages'), path.join(temp, 'upstream/packages'), {
    recursive: true, filter: name => !name.split(path.sep).some(p => p === 'node_modules' || p === '.git'),
  });
  fs.copyFileSync(path.join(resources, 'model-entry.ts'), path.join(temp, 'model-entry.ts'));
  fs.copyFileSync(path.join(__dirname, 'ai-barrel.ts'), path.join(temp, 'ai-barrel.ts'));
  const config = {
    context: temp, mode: 'none', target: 'web', devtool: false, entry: './model-entry.ts',
    output: { path: path.join(temp, 'output'), filename: 'pi-core-model.js' },
    optimization: { minimize: false, moduleIds: 'natural', chunkIds: 'natural' },
    module: { rules: [{ test: /\.ts$/, use: path.join(__dirname, 'strip-loader.cjs') }] },
    resolve: { extensions: ['.ts', '.js'], alias: {
      '@earendil-works/pi-agent-core$': path.join(temp, 'upstream/packages/agent/src/agent.ts'),
      '@earendil-works/pi-ai$': path.join(temp, 'ai-barrel.ts'),
      '@earendil-works/pi-schema$': path.join(temp, 'upstream/packages/schema/src/index.ts'),
      '@earendil-works/pi-schema/formats$': path.join(temp, 'upstream/packages/schema/src/formats/index.ts'),
    } }, performance: { hints: false },
  };
  webpack(config, (error, stats) => {
    try {
      if (error) throw error;
      if (stats.hasErrors()) throw Error(stats.toString({ colors: false }));
      const modules = stats.toJson({ modules: true }).modules;
      const inputs = modules.filter(m => m.nameForCondition && m.nameForCondition.startsWith(temp + path.sep))
        .map(m => ({ path: path.relative(temp, m.nameForCondition).split(path.sep).join('/'), sha256: sha(fs.readFileSync(m.nameForCondition)) }));
      inputs.push(...['build.cjs', 'strip-loader.cjs', 'ai-barrel.ts'].map(name => ({ path: 'build/' + name, sha256: sha(fs.readFileSync(path.join(__dirname, name))) })));
      manifest.inputs = inputs.sort((a, b) => a.path.localeCompare(b.path));
      const bundle = fs.readFileSync(path.join(temp, 'output/pi-core-model.js'));
      manifest.sha256 = sha(bundle);
      manifest.build = { ...manifest.build, node: process.versions.node, webpack: webpack.version, context: 'isolated temporary input tree', moduleIds: 'natural', builder: 'tools/pi-core/build.cjs' };
      fs.writeFileSync(path.join(resources, 'pi-core-model.js'), bundle);
      fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 2) + '\n');
      const port = path.join(repo, 'src/jvmMain/kotlin/borg/trikeshed/pi/PiModelPort.kt');
      fs.writeFileSync(port, fs.readFileSync(port, 'utf8').replace(/const val BUNDLE_SHA256 = "[0-9a-f]{64}"/, 'const val BUNDLE_SHA256 = "' + manifest.sha256 + '"'));
      console.log('Pi bundle SHA256 ' + manifest.sha256 + '; ' + inputs.length + ' input hashes');
    } catch (failure) { console.error(failure); process.exitCode = 1; }
    finally { fs.rmSync(temp, { recursive: true, force: true }); }
  });
} catch (failure) { fs.rmSync(temp, { recursive: true, force: true }); throw failure; }
