// Savepoint 构建脚本（无 Maven/Gradle，直接 javac 编译单文件插件）
// 用法: node build.cjs          只编译，产物在 dist/
//       node build.cjs test     编译并运行恢复逻辑校验（test/RestoreLogicTest.java）
//       node build.cjs deploy   编译并复制到服务器 plugins/
const fs = require('fs'), path = require('path'), { spawnSync } = require('child_process');

const here = __dirname;
const projects = path.resolve(here, '..', '..');           // .../projects
const server = process.env.MC_SERVER || path.join(projects, 'servers', 'paper-26.2');
const runtime = path.join(projects, 'servers', 'runtime');

function findJava(bin) {
  const jdk = fs.readdirSync(runtime).find(n => fs.existsSync(path.join(runtime, n, 'bin', bin)));
  if (!jdk) throw Error('no JDK under ' + runtime);
  return path.join(runtime, jdk, 'bin', bin);
}

function findJar(dir, patterns) {
  const out = [];
  (function walk(d) {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const p = path.join(d, e.name);
      if (e.isDirectory()) walk(p);
      else if (e.name.endsWith('.jar') && patterns.some(re => re.test(p))) out.push(p);
    }
  })(dir);
  return out;
}

const javac = findJava('javac.exe'), jar = findJava('jar.exe');const libs = path.join(server, 'libraries');
const cp = findJar(libs, [
  /paper-api.*\.jar$/,
  /net[\\/]kyori[\\/].*\.jar$/,
  /md-5[\\/].*\.jar$/,
  /snakeyaml.*\.jar$/,
  /jspecify.*\.jar$/,
]).join(path.delimiter);
if (!cp.includes('paper-api')) throw Error('paper-api not found under ' + libs);

const version = /version:\s*([\d.]+)/.exec(fs.readFileSync(path.join(here, 'resources', 'plugin.yml'), 'utf8'))[1];
const classes = path.join(here, 'build', 'classes');
const artifact = path.join(here, 'dist', `Savepoint-${version}.jar`);

fs.rmSync(path.join(here, 'build'), { recursive: true, force: true });
fs.mkdirSync(classes, { recursive: true });
fs.mkdirSync(path.join(here, 'dist'), { recursive: true });

const compile = spawnSync(javac, ['-encoding', 'UTF-8', '-cp', cp, '-d', classes,
  path.join(here, 'src', 'Savepoint.java')], { stdio: 'inherit' });
if (compile.status !== 0) process.exit(compile.status);

for (const r of ['plugin.yml', 'config.yml']) {
  fs.copyFileSync(path.join(here, 'resources', r), path.join(classes, r));
}
fs.rmSync(artifact, { force: true });
const pack = spawnSync(jar, ['cf', artifact, '-C', classes, '.'], { stdio: 'inherit' });
if (pack.status !== 0) process.exit(pack.status);

console.log('built: ' + artifact);

if (process.argv[2] === 'test') {
  const testClasses = path.join(here, 'build', 'test-classes');
  fs.rmSync(testClasses, { recursive: true, force: true });
  fs.mkdirSync(testClasses, { recursive: true });
  const tcp = classes + path.delimiter + cp;
  const compileTest = spawnSync(javac, ['-encoding', 'UTF-8', '-cp', tcp, '-d', testClasses,
    path.join(here, 'test', 'RestoreLogicTest.java')], { stdio: 'inherit' });
  if (compileTest.status !== 0) process.exit(compileTest.status);
  const run = spawnSync(findJava('java.exe'), ['-cp',
    testClasses + path.delimiter + tcp, 'RestoreLogicTest'], { stdio: 'inherit' });
  process.exit(run.status);
}

if (process.argv[2] === 'deploy') {
  const plugins = path.join(server, 'plugins');
  const dest = path.join(plugins, path.basename(artifact));
  fs.copyFileSync(artifact, dest);
  console.log('deployed to: ' + dest + ' (restart the server to load it)');
  const stale = fs.readdirSync(plugins).filter(n => /^(WorldBackup|UnitBackup).*\.jar$/.test(n));
  if (stale.length) {
    console.log('WARNING: still in plugins/: ' + stale.join(', ')
        + ' - remove them, two copies of this plugin would back up and lock logins side by side.');
  }
}
