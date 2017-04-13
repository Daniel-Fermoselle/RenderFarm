export JAVA_HOME=$(/usr/libexec/java_home -v 1.7)
export _JAVA_OPTIONS="-XX:-UseSplitVerifier "$_JAVA_OPTIONS
mkdir out
export CLASSPATH=$CLASSPATH:BIT/bit.jar:out/
echo "Compiling code to instrument"
javac -d out BIT/samples/RayTracerInstrument.java
echo "Compiling raytracer"
javac -d out/ raytracer-master/src/raytracer/pigments/*.java raytracer-master/src/raytracer/*.java raytracer-master/src/raytracer/shapes/*.java
cp -r BIT/BIT out
echo "Instrumenting the code:"
java RayTracerInstrument out/raytracer out/raytracer
cd out
jar -cf ../WebServer/libs/raytracer-master.jar BIT/highBIT/*.class BIT/lowBIT/*.class RayTracerInstrument.class raytracer/*.class raytracer/pigments/*.class raytracer/shapes/*.class
