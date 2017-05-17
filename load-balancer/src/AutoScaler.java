import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.cloudwatch.AmazonCloudWatch;
import com.amazonaws.services.cloudwatch.model.Datapoint;
import com.amazonaws.services.cloudwatch.model.Dimension;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsRequest;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsResult;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.*;

import java.util.*;

public class AutoScaler {

    private static final int MAX_INSTANCES = 5;
    private static final int MIN_INSTANCES = 2;

    private AmazonEC2 ec2;
    private AmazonDynamoDB dynamoDB;
    private AmazonCloudWatch cloudWatch;

    public AutoScaler(AmazonEC2 ec2, AmazonDynamoDB dynamoDB, AmazonCloudWatch cloudWatch) {
        this.ec2 = ec2;
        this.dynamoDB = dynamoDB;
        this.cloudWatch = cloudWatch;

        createInstance();
        createInstance();
    }

    public void updateInstances() {
        try {
            Set<Instance> instances = getAllInstances();

            int incCounter = 0;
            int decCounter = 0;
            System.out.println("total instances = " + instances.size());
            /* TODO total observation time in milliseconds */
            long offsetInMilliseconds = 1000 * 60 * 10;
            Dimension instanceDimension = new Dimension();
            instanceDimension.setName("InstanceId");
            List<Dimension> dims = new ArrayList<Dimension>();
            dims.add(instanceDimension);

            for (Instance instance : instances) {
                String name = instance.getInstanceId();
                String state = instance.getState().getName();

                if (state.equals("running")) {
                    System.out.println("running instance id = " + name);
                    instanceDimension.setValue(name);
                    GetMetricStatisticsRequest request = new GetMetricStatisticsRequest()
                            .withStartTime(new Date(new Date().getTime() - offsetInMilliseconds))
                            .withNamespace("AWS/EC2")
                            .withPeriod(60)
                            .withMetricName("CPUUtilization")
                            .withStatistics("Average")
                            .withDimensions(instanceDimension)
                            .withEndTime(new Date());
                    GetMetricStatisticsResult getMetricStatisticsResult = cloudWatch.getMetricStatistics(request);
                    List<Datapoint> datapoints = getMetricStatisticsResult.getDatapoints();
                    if(datapoints.size() > 0) {
                        System.out.println(" CPU utilization for instance " + name + " = " + datapoints.get(datapoints.size() - 1).getAverage());
                        if (datapoints.get(datapoints.size() - 1).getAverage() > 60) {
                            incCounter++;
                        }

                        if (datapoints.get(datapoints.size() - 1).getAverage() < 40) {
                            decCounter++;
                        }
                    }

                } else {
                    System.out.println("instance id = " + name);
                }

                System.out.println("Instance State : " + state + ".");
            }

            if(incCounter == instances.size() && instances.size() <= MAX_INSTANCES){
                createInstance();
            }

            if(decCounter == instances.size() && instances.size() >= MIN_INSTANCES){
                try {
                    killInstance(LoadBalancer.getFreeInstance());
                } catch (RuntimeException e){
                    if(e.getMessage().equals("No idle instances")){
                        System.out.println("No idle instances but machines < 40");
                    }
                    else {
                        e.printStackTrace();
                    }
                }
            }

        } catch (AmazonServiceException ase) {
            System.out.println("Caught Exception: " + ase.getMessage());
            System.out.println("Reponse Status Code: " + ase.getStatusCode());
            System.out.println("Error Code: " + ase.getErrorCode());
            System.out.println("Request ID: " + ase.getRequestId());
        }
    }

    private Set<Instance> getAllInstances() {
        //Specify the type of instance we want to get
        List<String> list = new ArrayList<>();
        list.add("16");
        Filter instanceStatusFilter = new Filter("instance-state-code", list);

        list = new ArrayList<>();
        list.add(LoadBalancer.INSTANCES_AMI_ID);
        Filter imageIDFilter = new Filter("image-id", list);

        Collection<Filter> filters = new ArrayList<>();
        filters.add(instanceStatusFilter);
        filters.add(imageIDFilter);

        DescribeInstancesRequest describeInstancesRequest = new DescribeInstancesRequest()
                .withFilters(filters);

        DescribeInstancesResult describeInstancesResult = ec2.describeInstances(describeInstancesRequest);
        List<Reservation> reservations = describeInstancesResult.getReservations();
        Set<Instance> instances = new HashSet<Instance>();

        System.out.println("total reservations = " + reservations.size());
        for (Reservation reservation : reservations) {
            instances.addAll(reservation.getInstances());
        }

        return instances;
    }

    private void createInstance() {
        RunInstancesRequest runInstancesRequest = new RunInstancesRequest();

        runInstancesRequest.withImageId(LoadBalancer.INSTANCES_AMI_ID)
                .withInstanceType("t2.micro")
                .withMinCount(1)
                .withMaxCount(1)
                .withKeyName("CNV-lab-AWS")
                .withSecurityGroups("CNV-ssh+http");

        ec2.runInstances(runInstancesRequest);
    }

    private void killInstance(String instanceID) {
        TerminateInstancesRequest termInstanceReq = new TerminateInstancesRequest();

        termInstanceReq.withInstanceIds(instanceID);
        ec2.terminateInstances(termInstanceReq);
    }
}
