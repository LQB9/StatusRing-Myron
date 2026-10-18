"""Run existing JVM scenarios after python build.py. Requires the same JDK/SDK."""
from pathlib import Path
import os,shutil,subprocess,re

root=Path(__file__).resolve().parent.parent
sdk=Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or '')
javac=shutil.which('javac')
java_home=os.environ.get('JAVA_HOME') or (str(Path(javac).resolve().parent.parent) if javac else None)
if not java_home:raise SystemExit('Set JAVA_HOME to JDK 21')
jdk=Path(java_home);exe='.exe' if os.name=='nt' else ''
platforms=list((sdk/'platforms').glob('android-*'))
if os.environ.get('ANDROID_JAR'):android=Path(os.environ['ANDROID_JAR'])
elif platforms:android=max(platforms,key=lambda p:tuple(int(n) for n in re.findall(r'\d+',p.name)))/'android.jar'
else:raise SystemExit('Set ANDROID_HOME or ANDROID_JAR')
classes=root/'build/classes';checks=root/'build/checks';checks.mkdir(parents=True,exist_ok=True)
if not (classes/'io/github/statusring/myron/Config.class').exists():raise SystemExit('Run python build.py first')
subprocess.run([str(jdk/('bin/javac'+exe)),'-encoding','UTF-8','-classpath',os.pathsep.join(map(str,[classes,android])),'-d',str(checks),str(root/'tests/CoreChecks.java')],check=True)
subprocess.run([str(jdk/('bin/java'+exe)),'-classpath',os.pathsep.join(map(str,[checks,classes])),'CoreChecks'],check=True)
