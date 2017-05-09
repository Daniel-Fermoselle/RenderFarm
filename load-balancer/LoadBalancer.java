import com.amazonaws.AmazonClientException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancing;
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient;
import com.amazonaws.services.elasticloadbalancing.model.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;


public class LoadBalancer {

    static AmazonEC2 ec2;
    static AmazonElasticLoadBalancing elb;


    /**
     * The only information needed to create a client are security credentials
     * consisting of the AWS Access Key ID and Secret Access Key. All other
     * configuration, such as the service endpoints, are performed
     * automatically. Client parameters, such as proxies, can be specified in an
     * optional ClientConfiguration object when constructing a client.
     *
     * @see com.amazonaws.auth.BasicAWSCredentials
     * @see com.amazonaws.auth.PropertiesCredentials
     * @see com.amazonaws.ClientConfiguration
     */
    private static void init() throws Exception {

        /*
         * The ProfileCredentialsProvider will return your [default]
         * credential profile by reading from the credentials file located at
         * (~/.aws/credentials).
         */
        AWSCredentials credentials = null;
        try {
            credentials = new ProfileCredentialsProvider().getCredentials();
        } catch (Exception e) {
            throw new AmazonClientException(
                    "Cannot load the credentials from the credential profiles file. " +
                            "Please make sure that your credentials file is at the correct " +
                            "location (~/.aws/credentials), and is in valid format.",
                    e);
        }
        ec2 = AmazonEC2ClientBuilder.standard().withRegion("eu-central-1").withCredentials(
                new AWSStaticCredentialsProvider(credentials)).build();
        elb = AmazonElasticLoadBalancingClient.builder().withRegion("eu-central-1").withCredentials(
                new AWSStaticCredentialsProvider(credentials)).build();
    }

    public static void main(String[] args) throws Exception {

        init();

        //create load balancer
        CreateLoadBalancerRequest lbRequest = new CreateLoadBalancerRequest();
        lbRequest.setLoadBalancerName("RayTracerBalancer");

        List<Listener> listeners = new ArrayList<>(1);
        listeners.add(new Listener("HTTP", 80, 8000));
        lbRequest.setListeners(listeners);

        String availabilityZone1 = "subnet-a83f45c0 - eu-central-1a";
        String availabilityZone2 = "subnet-586eed22 - eu-central-1b";
        lbRequest.withAvailabilityZones(availabilityZone1, availabilityZone2);

        List<String> securityGroups = new ArrayList<>(1);
        securityGroups.add("sg-2fa07044");
        lbRequest.setSecurityGroups(securityGroups);

        CreateLoadBalancerResult lbResult = elb.createLoadBalancer(lbRequest);
        System.out.println("created load balancer loader");

        //get the running instances
        DescribeInstancesResult describeInstancesRequest = ec2.describeInstances();
        List<Reservation> reservations = describeInstancesRequest.getReservations();
        List<Instance> instances = new ArrayList<Instance>();

        for (Reservation reservation : reservations) {
            instances.addAll(reservation.getInstances());
        }

        //get instance id's
        String id;
        List instanceId = new ArrayList();
        Iterator<Instance> iterator = instances.iterator();
        while (iterator.hasNext()) {
            id = iterator.next().getInstanceId();
            instanceId.add(new com.amazonaws.services.elasticloadbalancing.model.Instance(id));
        }

        //register the instances to the balancer
        RegisterInstancesWithLoadBalancerRequest register = new RegisterInstancesWithLoadBalancerRequest();
        register.setLoadBalancerName("RayTracerBalancer");
        register.setInstances(instanceId);
        RegisterInstancesWithLoadBalancerResult registerWithLoadBalancerResult = elb.registerInstancesWithLoadBalancer(register);
    }
}
