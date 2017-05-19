Group 10

Our project is composed by 6 shell scripts:

- send.sh: this script will compress the project into a tar.gz and send it to the web folder of sigma. This is needed because the next script will perform a wget to get the project. (If you want to test this local or you have other means of getting our code this script won't be needed to execute the project).

- configForAWS: this script gets the code (wget) from the link specified in the script send.sh and executes the WebServer. Make sure that the links on the first line of the script are correct, according to the url where the code is located. We use this script by copying its content to the rc.local of the amazon EC2 instances.

- configForAWSLB: this script does the same as configForAWS but instead of running a WebServer it runs the LoadBalancer code.

- instrument.sh: this script is used to compile and instrument our code so we can get the metrics from the raytracer.

- WebServer/runServer.sh: this script is used to compile and run the WebServer with all the correct environment variables.

- load-balancer/run.sh: this script is used to compile and run the LoadBalancer with all the correct environment variables witch also launches 2 WebServer instances (need to configure the AMI ID in the code).

Note that if you want to run the project locally you only need to execute instrument.sh followed send.sh and by load-balancer/run.sh if you have configured in the code of the load-balancer the constant to use your AMI ID from your WebServer instances.
	./instrument.sh
	./load-balancer/run.sh

Otherwise you should first instrument and compile (instrument.sh) the code then send it (send.sh) and execute it on an AWS server by copying the content of configForAWS to the rc.local file in the etc folder of the instances of WebServer and copying the configForAWSLB to the rc.local of your load-balancer instances.
	./instrument.sh
	./send.sh

There is a JMeter script inside the JMeterScript folder.
