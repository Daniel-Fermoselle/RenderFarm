import java.util.ArrayList;

public class Heuristic {
	
	//Calculates the rank of an instance in order to detect the need of creating or deleting the instance
	public static ArrayList<Integer> calculateRank(ArrayList<Double> methodsCount, ArrayList<Double> successFactor){
		ArrayList<Integer> instanceRanks = new ArrayList<Integer>();
        for (int i = 0; i < methodsCount.size(); i++) {
            if (methodsCount.get(i) != -1 && successFactor.get(i) != -1) {
                double x = (methodsCount.get(i) * ((100 + successFactor.get(i)) / 100)) / 1000000;
                instanceRanks.add(getRank(x));
            }
            else {
                return null;
            }
        }
        return instanceRanks;
    }

	//Calculates the rank of an instance in order to detect if it can handle another request or not
    public static ArrayList<Integer> calculateRankToSend(ArrayList<Double> methodsCount, ArrayList<Double> successFactor){
        ArrayList<Integer> instanceRanks = new ArrayList<Integer>();
        for (int i = 0; i < methodsCount.size(); i++) {
            if (methodsCount.get(i) != -1 && successFactor.get(i) != -1) {
                double x = (methodsCount.get(i) * ((100 + successFactor.get(i)) / 100)) / 1000000;
                instanceRanks.add(getRankToSend(x));
            }
            else {
                return null;
            }
        }
        return instanceRanks;
    }

    //Gets the rank to create
    private static int getRank(double x){
		//Rank 4
		if(x < 1){
			return 5;
		}
		//Rank 3
		else if(x < 10){
			return 15;
		}
		//Rank 2
		else if(x < 16){
			return 34;
		}
		//Rank 1
		else if ( x >= 16){
			return 50;
		}
		else{
			throw new RuntimeException("Invalid value to calculate rank value. x ->" + x);
		}
	}
	
    //Based on the create ranks of the machine verifies if it is needed to create another machine
	public static boolean needToCreateInstance(ArrayList<Integer> ranks){
		int sumRanks = 0;
		for(int rank : ranks){
			sumRanks += rank;
		}

		if(sumRanks >= 100){
			return true;
		}
		return false;
	}

	//Gets the rank to send
    private static int getRankToSend(double x){
        //Rank 4
        if(x < 1){
            return 5;
        }
        //Rank 3
        else if(x < 10){
            return 15;
        }
        //Rank 2
        else if(x < 16){
            return 26;
        }
        //Rank 1
        else if ( x >= 16){
            return 50;
        }
        else{
            throw new RuntimeException("Invalid value to calculate rank value. x ->" + x);
        }
    }

  //Based on the send ranks of the machine verifies if the machine can handle another request
	public static boolean canSendRequest(ArrayList<Integer> ranks){
		int sumRanks = 0;
		for(int rank : ranks){
			sumRanks += rank;
		}

		if(sumRanks > 100){
			return true;
		}
		return false;
	}
}
