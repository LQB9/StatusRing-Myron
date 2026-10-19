"""Standalone Android build; needs JDK 21, Android build-tools 37, API 36+ and libxposed 102 jars."""
from pathlib import Path
import os,subprocess,zipfile,secrets,json,hashlib,re,shutil

ROOT=Path(__file__).resolve().parent
sdk_path=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
if not sdk_path:raise SystemExit('Set ANDROID_HOME to your Android SDK directory')
SDK=Path(sdk_path)
javac=shutil.which('javac')
java_path=os.environ.get('JAVA_HOME') or (str(Path(javac).resolve().parent.parent) if javac else None)
if not java_path:raise SystemExit('Set JAVA_HOME to JDK 21 or put javac on PATH')
JDK=Path(java_path)
def latest(paths):
    candidates=list(paths)
    if not candidates:raise SystemExit('Required Android SDK tools or platform missing')
    return max(candidates,key=lambda item:tuple(int(n) for n in re.findall(r'\d+',item.name)))
BT=SDK/'build-tools'/os.environ['ANDROID_BUILD_TOOLS_VERSION'] if os.environ.get('ANDROID_BUILD_TOOLS_VERSION') else latest((SDK/'build-tools').glob('*'))
ANDROID=Path(os.environ['ANDROID_JAR']) if os.environ.get('ANDROID_JAR') else latest((SDK/'platforms').glob('android-*'))/'android.jar'
OUT=ROOT/'build';OUT.mkdir(exist_ok=True)
for name in ['classes','dex']: (OUT/name).mkdir(exist_ok=True)
env=os.environ.copy();env['JAVA_HOME']=str(JDK);env['PATH']=str(JDK/'bin')+os.pathsep+env.get('PATH','')
def run(command):
    p=subprocess.run([str(x) for x in command],env=env,capture_output=True,text=True,encoding='utf-8',errors='replace')
    if p.stdout.strip():print(p.stdout.strip(),flush=True)
    if p.stderr.strip():print(p.stderr.strip(),flush=True)
    if p.returncode:raise SystemExit(p.returncode)
libs=ROOT/'libs';api=libs/'api-102.jar';interface=libs/'interface-102.jar';service=libs/'service-102.jar'
for item in [ANDROID,api,interface,service]:
    if not item.exists():raise SystemExit('Missing dependency: '+str(item))
cp=os.pathsep.join(map(str,[ANDROID,api,interface,service]))
exe='.exe' if os.name=='nt' else '';bat='.bat' if os.name=='nt' else ''
run([JDK/('bin/javac'+exe),'--release','8','-encoding','UTF-8','-classpath',cp,'-d',OUT/'classes',*sorted((ROOT/'src').rglob('*.java'))])
run([JDK/('bin/jar'+exe),'cf',OUT/'classes.jar','-C',OUT/'classes','.'])
run([BT/('d8'+bat),'--release','--min-api','31','--lib',ANDROID,'--lib',api,'--output',OUT/'dex',OUT/'classes.jar',interface,service])
run([BT/('aapt2'+exe),'compile','--dir',ROOT/'res','-o',OUT/'resources.zip'])
run([BT/('aapt2'+exe),'link','-o',OUT/'unsigned.apk','-I',ANDROID,'--manifest',ROOT/'AndroidManifest.xml',OUT/'resources.zip'])
with zipfile.ZipFile(OUT/'unsigned.apk','a',compression=zipfile.ZIP_DEFLATED) as z:
    z.write(OUT/'dex/classes.dex','classes.dex')
    for f in sorted((ROOT/'xposed').iterdir()):z.write(f,'META-INF/xposed/'+f.name)
run([BT/('zipalign'+exe),'-f','-p','4',OUT/'unsigned.apk',OUT/'aligned.apk'])
key=ROOT.parent/'statusring-signing.jks';passfile=ROOT.parent/'.statusring-sign-password'
if not key.exists():
    password=secrets.token_urlsafe(28);passfile.write_text(password,encoding='utf-8')
    env['STATUSRING_SIGN_PASSWORD']=password
    run([JDK/('bin/keytool'+exe),'-genkeypair','-keystore',key,'-alias','statusring','-storepass:env','STATUSRING_SIGN_PASSWORD','-keypass:env','STATUSRING_SIGN_PASSWORD','-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=StatusRing Personal Development'])
env['STATUSRING_SIGN_PASSWORD']=passfile.read_text(encoding='utf-8').strip()
apk=OUT/'StatusRing-Myron-v1.0.14.apk'
run([BT/('apksigner'+bat),'sign','--ks',key,'--ks-pass','env:STATUSRING_SIGN_PASSWORD','--key-pass','env:STATUSRING_SIGN_PASSWORD','--out',apk,OUT/'aligned.apk'])
run([BT/('apksigner'+bat),'verify','--verbose','--print-certs',apk])
run([BT/('zipalign'+exe),'-c','-v','4',apk])
print(json.dumps({'apk':str(apk),'size':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest()}))
