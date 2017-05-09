Group 10

Our project is composed by 4 shell scripts:

- send.sh: this script will compress the project into a tar.gz and send it to the web folder of sigma. This is needed because the next script will perform a wget to get the project. (If you want to test this local or you have other means of getting our code this script won't be needed to execute the project).

- configForAWS: this script gets the code (wget) from the link specified in the script send.sh and executes the WebServer. Make sure that the links on the first line of the script are correct, according to the url where the code is located. We use this script by copying its content to the rc.local of the amazon EC2 instances.

- instrument.sh: this script is used to compile and instrument our code so we can get the metrics from the raytracer.

- WebServer/runServer.sh: this script is used to compile and run the WebServer with all the correct environment variables.

Note that if you want to run the project locally you only need to execute instrument.sh followed by WebServer/runServer.sh. 
	./instrument.sh
	./WebServer/runServer.sh

Otherwise you should first instrument and compile (instrument.sh) the code then send it (send.sh) and execute it on an AWS server by copying the content of configForAWS to the rc.local file in the etc folder.
	./instrument.sh
	./send.sh
	