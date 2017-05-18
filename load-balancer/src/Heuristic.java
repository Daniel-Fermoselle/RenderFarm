import java.util.ArrayList;

public class Heuristic {
	
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
			return 24;
		}
		//Rank 1
		else if ( x >= 16){
			return 50;
		}
		else{
			throw new RuntimeException("Invalid value to calculate rank value. x ->" + x);
		}
	}
	
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
}
