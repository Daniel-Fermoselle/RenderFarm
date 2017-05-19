wget --directory-prefix=/home/ec2-user/ http://web.tecnico.ulisboa.pt/\~ist178471/proj.tar.gz
tar -xzvf /home/ec2-user/proj.tar.gz -C /home/ec2-user/
rm -f /home/ec2-user/proj.tar.gz
export _JAVA_OPTIONS="-XX:-UseSplitVerifier "$_JAVA_OPTIONS
export CLASSPATH='/home/ec2-user/RenderFarm/WebServer/libs/commons-codec-1.10.jar:/home/ec2-user/RenderFarm/WebServer/libs/raytracer-master.jar:/home/ec2-user/RenderFarm/load-balancer/lib/aws-java-sdk-1.11.126.jar:/home/ec2-user/RenderFarm/load-balancer/third-party/lib/*:.'
rm -f /home/ec2-user/RenderFarm/load-balancer/third-party/lib/._*
javac -d . /home/ec2-user/RenderFarm/WebServer/src/WebServer.java
mkdir inputs
cp /home/ec2-user/RenderFarm/WebServer/inputs/*.txt inputs/
java -Djava.awt.headless=true WebServer
