wget --directory-prefix=/home/ec2-user/ http://web.tecnico.ulisboa.pt/\~ist178471/proj.tar.gz
tar -xzvf /home/ec2-user/proj.tar.gz -C /home/ec2-user/
rm -f /home/ec2-user/proj.tar.gz
export _JAVA_OPTIONS="-XX:-UseSplitVerifier "$_JAVA_OPTIONS
export CLASSPATH='/home/ec2-user/RenderFarm/load-balancer/lib/aws-java-sdk-1.11.126.jar:/home/ec2-user/RenderFarm/load-balancer/third-party/lib/*:.'
rm -f /home/ec2-user/RenderFarm/load-balancer/third-party/lib/._*
javac -d . /home/ec2-user/RenderFarm/load-balancer/src/LoadBalancer.java /home/ec2-user/RenderFarm/load-balancer/src/AutoScaler.java /home/ec2-user/RenderFarm/load-balancer/src/RayTracerLBHandler.java /home/ec2-user/RenderFarm/load-balancer/src/Heuristic.java
java LoadBalancer
