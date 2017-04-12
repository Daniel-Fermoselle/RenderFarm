export JAVA_HOME=$(/usr/libexec/java_home -v 1.7)
export _JAVA_OPTIONS="-XX:-UseSplitVerifier "$_JAVA_OPTIONS
mkdir out
export CLASSPATH=libs/commons-codec-1.10.jar:libs/raytracer-master.jar:out
javac -d out src/WebServer.java
java -Djava.awt.headless=true WebServer
