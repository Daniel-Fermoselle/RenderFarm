
/* ICount.java
* Sample program using BIT -- counts the number of instructions executed.
*
* Copyright (c) 1997, The Regents of the University of Colorado. All
* Rights Reserved.
* 
* Permission to use and copy this software and its documentation for
* NON-COMMERCIAL purposes and without fee is hereby granted provided
* that this copyright notice appears in all copies. If you wish to use
* or wish to have others use BIT for commercial purposes please contact,
* Stephen V. O'Neil, Director, Office of Technology Transfer at the
* University of Colorado at Boulder (303) 492-5647.
*/

import BIT.highBIT.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

public class ICount {
	private static PrintStream out = null;
	private static long intersections = 0;
	private static long successfulIntersections = 0;

	/*
	 * main reads in all the files class files present in the input directory,
	 * instruments them, and outputs them to the specified output directory.
	 */
	public static void main(String argv[]) {
		String shapesInputPath = argv[0] + System.getProperty("file.separator") + "shapes";
		String shapesOutputPath = argv[1] + System.getProperty("file.separator") + "shapes";
		File file_in = new File(shapesInputPath);
		String infilenames[] = file_in.list();

		for (int i = 0; i < infilenames.length; i++) {
			String infilename = infilenames[i];
			if (infilename.endsWith(".class")) {
				// create class info object
				ClassInfo ci = new ClassInfo(shapesInputPath + System.getProperty("file.separator") + infilename);
				// loop through all the routines
				if ("raytracer/shapes/Shape".equals(ci.getSuperClassName())) {
					System.out.println(ci.getClassName() + "<----------- It's me");
					for (Enumeration e = ci.getRoutines().elements(); e.hasMoreElements();) {
						Routine routine = (Routine) e.nextElement();
						if (routine.getMethodName().startsWith("intersect")) {
							System.out.println(
									routine.getMethodName() + "<----------- It's my intersect " + ci.getClassName());
							routine.addBefore("ICount", "incIntersections", "Nothing");
						}
					}
				}
				ci.write(shapesOutputPath + System.getProperty("file.separator") + infilename);
			}
		}

		file_in = new File(argv[0]);
		infilenames = file_in.list();

		for (int i = 0; i < infilenames.length; i++) {
			String infilename = infilenames[i];
			if (infilename.endsWith("Main.class") || infilename.endsWith("RayTracer.class")) {
				ClassInfo ci = new ClassInfo(argv[0] + System.getProperty("file.separator") + infilename);
				// loop through all the routines
				// see java.util.Enumeration for more information on Enumeration
				// class
				for (Enumeration e = ci.getRoutines().elements(); e.hasMoreElements();) {
					Routine routine = (Routine) e.nextElement();
					if (routine.getMethodName().startsWith("render")) {
						routine.addAfter("ICount", "writeToFile", "Nothing");
					}
					else if (routine.getMethodName().startsWith("shade")) {
						routine.addBefore("ICount", "incSuccessfulIntersections", "Nothing");
					}
				}
				ci.write(argv[1] + System.getProperty("file.separator") + infilename);
			}
		}

	}

	public static synchronized void incIntersections(String methodName) {
		intersections++;
	}
	
	public static synchronized void incSuccessfulIntersections(String methodName) {
		successfulIntersections++;
	}

	public static synchronized void writeToFile(String s) {
		try {
			Long i = new Long(intersections);
			Long si = new Long(successfulIntersections);
			Double successFactor = new Double(si*100.0/i);
			String toWrite = "intersections=" + i.toString() + "\n" +
							 "successfulIntersections=" + si.toString() + "\n" +
							 "successFactor=" + successFactor + "\n";
			Files.write(Paths.get("metadata.in"), toWrite.getBytes(), StandardOpenOption.APPEND);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

}