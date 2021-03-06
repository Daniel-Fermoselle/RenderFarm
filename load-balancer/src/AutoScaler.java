import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.model.*;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.*;

import java.util.*;

public class AutoScaler {

    private static final int MAX_INSTANCES = 5;
    private static final int MIN_INSTANCES = 2;
    private static final double ESTIMATION_CONSTANT = 14.9622;

    private AmazonEC2 ec2;
    private static AmazonDynamoDB dynamoDB;

    public AutoScaler(AmazonEC2 ec2, AmazonDynamoDB dynamoDB) {
        this.ec2 = ec2;
        this.dynamoDB = dynamoDB;

        createInstance();
        createInstance();
    }

    //Creates or deletes instances by necessity
    public void updateInstances() {
        try {
            Set<Instance> instances = getAllInstances();

            int incCounter = 0;
            int decCounter = 0;
            System.out.println("Total instances = " + instances.size());

            for (Instance instance : instances) {
                boolean weight = weight(instance);
                if(!weight) {
                    System.out.println("Instance " + instance.getPrivateIpAddress() + " is ok with the work it has");
                }
                else {
                    System.out.println("Instance " + instance.getPrivateIpAddress() + " needs a friend to help it");
                }

                if (weight){
                    incCounter++;
                }
                else{
                    decCounter++;
                }
            }

            if(incCounter > (instances.size() * 0.7) && instances.size() <= MAX_INSTANCES && instances.size() != 0){
                createInstance();
            }
            else if(decCounter > (instances.size() * 0.7) && instances.size() > MIN_INSTANCES){
                try {
                    Instance i = LoadBalancer.getFreeInstance();
                    System.out.println("THIS IS THE MACHINE CHOSEN TO BE SACRIFICED " + i.getPrivateIpAddress());
                    killInstance(i.getInstanceId());
                } catch (RuntimeException e){
                    if(e.getMessage().equals("No idle instances")){
                        System.out.println("No idle instances but machines returned enough falses");
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

    //Gets all the instances which are alive and have the specified ami
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

    //Calculates whether or not it is necessary to create another instance
    private boolean weight(Instance i){
        ArrayList<String> runningRequests = LoadBalancer.getRunningRequests(i);
        ArrayList<Double> methodCount = new ArrayList<>();
        ArrayList<Double> successFactor = new ArrayList<>();

        if (runningRequests == null || runningRequests.size() == 0) {
            return false;
        }

        for (String runningRequest : runningRequests) {
            HashMap<String, String> request = LoadBalancer.processQuery(runningRequest);

            Double nbMethodCount = getMethodCounts(request);
            Double nbSuccessFactor = getSuccessFactor(request);

            if(nbMethodCount == -1.0) {
                nbMethodCount = estimateNbMethodCount(request);
            }

            if (LoadBalancer.DEBUG) {
                System.out.println("For request " + runningRequest);
                System.out.println("\tnbMethodCount = " + nbMethodCount);
                System.out.println("\tnbSuccessFactor = " + nbSuccessFactor);
            }

            //Query and get methodCount
            methodCount.add(nbMethodCount);

            //Query and get successFactor
            successFactor.add(nbSuccessFactor);
        }

        ArrayList<Integer> integers = Heuristic.calculateRank(methodCount, successFactor);

        if (integers == null) {
            return false;
        }

        return Heuristic.needToCreateInstance(integers);
    }

    //Query to get the number of methods called to draw the request returns -1.0 if no data found
    public static Double getMethodCounts(HashMap<String, String> request) {
        HashMap<String, Condition> scanFilter = new HashMap<String, Condition>();
        long resolution = Long.parseLong(request.get("wc")) * Long.parseLong(request.get("wr"));
        Condition condition = new Condition().withComparisonOperator(ComparisonOperator.EQ.toString())
                .withAttributeValueList(new AttributeValue().withS(request.get("f") + "-" + resolution + ""));
        scanFilter.put("filename-resolution", condition);

        ScanRequest scanRequest = new ScanRequest(LoadBalancer.TABLE_NAME_COUNT).withScanFilter(scanFilter);
        ScanResult scanResult = dynamoDB.scan(scanRequest);
        for (Map<String, AttributeValue> maps : scanResult.getItems()) {
            return Double.parseDouble(maps.get("count").getS()); //MAYBE WE NEED MORE VERIFICATIONS
        }

        return -1.0;
    }

    //Query to get the success factor to draw the request returns -1.0 if no data found
    public static Double getSuccessFactor(HashMap<String, String> request) {
        HashMap<String, Condition> scanFilter = new HashMap<String, Condition>();
        Condition condition = new Condition().withComparisonOperator(ComparisonOperator.EQ.toString())
                .withAttributeValueList(new AttributeValue().withS(request.get("f")));
        scanFilter.put("filename", condition);

        ScanRequest scanRequest = new ScanRequest(LoadBalancer.TABLE_NAME_SUCCESS).withScanFilter(scanFilter);
        ScanResult scanResult = dynamoDB.scan(scanRequest);
        for (Map<String, AttributeValue> maps : scanResult.getItems()) {
            return Double.parseDouble(maps.get("success-factor").getS()); //MAYBE WE NEED MORE VERIFICATIONS
        }

        return -1.0;
    }

    //Estimates the number of methods called based on an empiric constant and resolution of the request
    public static Double estimateNbMethodCount(HashMap<String, String> request) {
        long resolution = Long.parseLong(request.get("wc")) * Long.parseLong(request.get("wr"));

        return ESTIMATION_CONSTANT * resolution;
    }


}
