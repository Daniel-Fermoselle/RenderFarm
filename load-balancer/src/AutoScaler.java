import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.cloudwatch.AmazonCloudWatch;
import com.amazonaws.services.cloudwatch.model.Datapoint;
import com.amazonaws.services.cloudwatch.model.Dimension;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsRequest;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsResult;
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

            for (Instance instance : instances) {
                System.out.println("Instance " + instance.getPrivateIpAddress() + " said " + weight(instance));

                if (weight(instance)){
                    incCounter++;
                }
                else{
                    decCounter++;
                }
            }

            if(incCounter >= (instances.size()/2) && instances.size() <= MAX_INSTANCES && instances.size() != 0){
                createInstance();
            }
            else if(decCounter > (instances.size()/2) && instances.size() > MIN_INSTANCES){
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

    //0 - 100
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

            System.out.println("For request " + runningRequest);
            System.out.println("\tnbMethodCount = " + nbMethodCount);
            System.out.println("\tnbSuccessFactor = " + nbSuccessFactor);

            //Query and get methodCount
            if(nbMethodCount != -1.0) {
                methodCount.add(nbMethodCount);
            }
            else {
                methodCount.add(estimateNbMethodCount(request));
            }
            //Query and get successFactor
            successFactor.add(nbSuccessFactor);
        }

        ArrayList<Integer> integers = Heuristic.calculateRank(methodCount, successFactor);

        if (integers == null) {
            return false;
        }

        return Heuristic.needToCreateInstance(integers);
    }

    private Double estimateNbMethodCount(HashMap<String, String> request) {
        long resolution = Long.parseLong(request.get("wc")) * Long.parseLong(request.get("wr"));

        return ESTIMATION_CONSTANT * resolution;
    }

    private Double getMethodCounts(HashMap<String, String> request) {
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

    private Double getSuccessFactor(HashMap<String, String> request) {
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
}
