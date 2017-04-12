export JAVA_HOME=$(/usr/libexec/java_home -v 1.7)
export _JAVA_OPTIONS="-XX:-UseSplitVerifier "$_JAVA_OPTIONS
cp -r BIT/BIT out
export CLASSPATH=BIT/bit.jar:out/
java ICount out/raytracer/ out/raytracer/
cd out
jar -cf ../WebServer/libs/raytracer-master.jar BIT/highBIT/*.class BIT/lowBIT/*.class ICount.class raytracer/*.class raytracer/pigments/*.class raytracer/shapes/*.class
