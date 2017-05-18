export JAVA_HOME=$(/usr/libexec/java_home -v 1.7)
export _JAVA_OPTIONS="-XX:-UseSplitVerifier "$_JAVA_OPTIONS
mkdir out
export CLASSPATH='lib/aws-java-sdk-1.11.126.jar:third-party/lib/*:out'
javac -d out src/LoadBalancer.java src/AutoScaler.java src/Heuristic.java
java LoadBalancer
