import BIT.highBIT.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;


public class ICount {
    private final static String TEMPLATE_INIT_MAIN = "==============================\n"+
    											   "=====INSTRUMENTATION INIT=====\n"+
    											   "==============================\n";
    private final static String TEMPLATE_RAYTRACER_CALL = "==============================\n"+
		 	   								   			"========RAYTRACER INIT========\n"+
		 	   								   			"==============================\n";
    private final static String TEMPLATE_RAYTRACER_CALL_END = "==============================\n"+
	   													   "========RAYTRACER END=========\n"+
	   													   "==============================\n";
    private static String template = "";
    private static int counter = 0;

    /* main reads in all the files class files present in the input directory,
     * instruments them, and outputs them to the specified output directory.
     */
    public static void main(String argv[]) {
        File file_in = new File(argv[0]);
        String infilenames[] = file_in.list();

        for (int i = 0; i < infilenames.length; i++) {
            String infilename = infilenames[i];
            if (infilename.startsWith("Main.class")) {
                // create class info object
                ClassInfo ci = new ClassInfo(argv[0] + System.getProperty("file.separator") + infilename);
                // loop through all the routines
                // see java.util.Enumeration for more information on Enumeration class
                ci.addBefore("ICount", "addTemplateInitMain", "Main");
                for (Enumeration e = ci.getRoutines().elements(); e.hasMoreElements(); ) {
                    Routine routine = (Routine) e.nextElement();
                    routine.addBefore("ICount", "methodIn", routine.getMethodName());
                    routine.addAfter("ICount", "methodOut", routine.getMethodName());
                    if (routine.getMethodName().startsWith("render")) {
                        routine.addAfter("ICount", "writeToFile", "Main");
                    }
                }


                ci.write(argv[1] + System.getProperty("file.separator") + infilename);
            }
            
            if (infilename.startsWith("RayTracer.class")) {
                // create class info object
                ClassInfo ci = new ClassInfo(argv[0] + System.getProperty("file.separator") + infilename);

                // loop through all the routines
                // see java.util.Enumeration for more information on Enumeration class
                for (Enumeration e = ci.getRoutines().elements(); e.hasMoreElements(); ) {
                    Routine routine = (Routine) e.nextElement();
                    routine.addBefore("ICount", "methodCount", routine.getMethodName());
                    if (routine.getMethodName().startsWith("<init>")) {
                    	routine.addAfter("ICount", "getClassArgs", "ola");
                    }
                    if (routine.getMethodName().startsWith("draw")) {
                    	routine.addAfter("ICount", "classCount", ci.getClassName());
                        routine.addAfter("ICount", "writeToFile", "RayTracer");
                    }
                }


                ci.write(argv[1] + System.getProperty("file.separator") + infilename);
            }
        }
    }

    public static synchronized void methodIn(String methodName) {
        template += "Entrei no metodo " + methodName + " Sou a thread " + Thread.currentThread().getId() + "\n";
    }

    public static synchronized void methodOut(String methodName) {
        template += "Sai do metodo " + methodName + " Sou a thread " + Thread.currentThread().getId() + "\n";
    }
    
    public static synchronized void methodCount(String methodName) {
        counter+=1;
    }
    
    public static synchronized void classCount(String methodName) {
    	template += TEMPLATE_RAYTRACER_CALL;
        template += "For class " + methodName + " in thread " + Thread.currentThread().getId() 
        		+ " there were " + counter + " methods run.\n";
        counter=0;
        template += TEMPLATE_RAYTRACER_CALL_END;
    }
    
    public static synchronized void addTemplateInitMain(String methodName) {
        template += TEMPLATE_INIT_MAIN;
    }
    
    public static synchronized void getClassArgs(String methodName) {
    	template += "============INIT================\n";
    }
   
    public static synchronized void writeToFile(String ola) {
        try {
            Files.write(Paths.get("metadata.in"), template.getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
        
    }

}