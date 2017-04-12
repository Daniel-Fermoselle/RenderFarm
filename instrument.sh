export JAVA_HOME=$(/usr/libexec/java_home -v 1.7)
export _JAVA_OPTIONS="-XX:-UseSplitVerifier "$_JAVA_OPTIONS
mkdir out
export CLASSPATH=$CLASSPATH:BIT/bit.jar:out/
javac -d out BIT/samples/ICount.java
javac -d out/ raytracer-master/src/raytracer/pigments/*.java raytracer-master/src/raytracer/*.java raytracer-master/src/raytracer/shapes/*.java
cp -r BIT/BIT out
java ICount out/raytracer/ out/raytracer/
cd out
jar -cf ../WebServer/libs/raytracer-master.jar BIT/highBIT/*.class BIT/lowBIT/*.class ICount.class raytracer/*.class raytracer/pigments/*.class raytracer/shapes/*.class
