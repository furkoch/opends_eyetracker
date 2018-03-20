package eu.opends.drivesense;

import java.io.FileWriter;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;

import com.jme3.math.Vector2f;
import com.mysql.jdbc.StringUtils;

import antlr.collections.List;
import eu.opends.drivesense.domain.GazeData;
import eu.opends.drivesense.domain.GazePosition;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public class EyeTrackerIO {	
	
	private GazeData etData;
	public static FileWriter fileWriter;
	
	//Delimiter used in CSV file

	 	private static final String ANNOTATIONS_FILENAME = "aoi_labels.csv";
	    private static final String COLUMN_DELIMITER = ",";
	    private static final String NEW_LINE_SEPARATOR = "\n";
	    private static final String FILE_HEADER = "Surface,AOI,Timestamps";	    
	    private static final String ABSOLUTE_PATH = "C:\\Users\\drivesense\\recordings\\";
	    private static final NumberFormat nf3 = new DecimalFormat("000");
	    private String recPath = null;
	
	public EyeTrackerIO(){	
	 
	}
	
	public void writeHeaderData(){
		
		try {
			String filePath = this.findLastDir();
			filePath = filePath.concat(ANNOTATIONS_FILENAME);
			
			fileWriter = new FileWriter(new File(filePath));
			
			//Write the CSV file header
			fileWriter.append(FILE_HEADER);				
			
			//Add a new line separator after the header
			fileWriter.append(NEW_LINE_SEPARATOR);	
			
			fileWriter.flush();
			//fileWriter.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	public void initWriting(String filename){
		try {
			fileWriter = new FileWriter(new File(filename+"\\"+ANNOTATIONS_FILENAME));
		} catch (IOException e) {
			// TODO Auto-generated catch block
			//recPath = ABSOLUTE_PATH + "\\" + ANNOTATIONS_FILENAME;
			e.printStackTrace();
		} 
		
	}
	
	/**
	 * Write gaze-data to the CSV file
	* */
	public void writeGazeToCSV(GazePosition etData){		
		
		try {
			
			fileWriter.append(etData.getSrf());
			fileWriter.append(COLUMN_DELIMITER);
			
			fileWriter.append(etData.getAOI());
			fileWriter.append(COLUMN_DELIMITER);
			
			int nTimestamps = etData.getTimestamps().size();
			
			for(int i=0; i<nTimestamps-1;i++){
				double ts = etData.getTimestamps().get(i);
				fileWriter.append(String.valueOf(ts));
				fileWriter.append(COLUMN_DELIMITER);				
			}
			
			double lastVal = etData.getTimestamps().get(nTimestamps-1);
			fileWriter.append(String.valueOf(lastVal));
			fileWriter.append(NEW_LINE_SEPARATOR);			
			
		} catch (IOException e) {
			// TODO Auto-generated catch block
			System.out.println("Error in CsvFileWriter !!!"); 
			e.printStackTrace();
		}finally {			
			try {
				fileWriter.flush();
			} catch (IOException e) {
				System.out.println("Error while flushing/closing fileWriter !!!");
                e.printStackTrace();
			}
		}								
	}
	
	public String createRecDir() {
		
		Date today = new Date();		
		SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy_MM_dd");
		String dateDir = DATE_FORMAT.format(today);
			
		this.recPath = ABSOLUTE_PATH.concat(dateDir);		
		File recDir = new File(this.recPath);
		
		if(!recDir.exists())
		{
			//Create a new directory
			recDir.mkdir();
		}
		
		File[] subDirs = new File(this.recPath).listFiles();
		//Create a new subdir
		this.recPath += "\\"+nf3.format(subDirs.length);
		(new File(this.recPath)).mkdir();
		
		FileWriter fileWriter;
		try {
			fileWriter = new FileWriter(new File(this.recPath.concat("\\").concat(ANNOTATIONS_FILENAME)));
			fileWriter.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return this.recPath;
	}
	
	
	public static void readCsvFile(String fileName) {

		BufferedReader fileReader = null;
     
        try {
        	
        	//Create a new list of student to be filled by CSV file data 
        	ArrayList<GazePosition> gazePositions = new ArrayList<>();
        	
            String line = "";
            
            //Create the file reader
            fileReader = new BufferedReader(new FileReader(fileName));
            
            //Read the CSV file header to skip it
            fileReader.readLine();
            
            //Read the file line by line starting from the second line
            while ((line = fileReader.readLine()) != null) {
                //Get all tokens available in line
                String[] tokens = line.split(COLUMN_DELIMITER);
                if(tokens.length==0)
                	continue;                
                // "Surface,AOI";       
                GazePosition gazePos = new GazePosition(tokens[0],tokens[1]);
                //Timestamps
                for(int i=2;i<tokens.length;i++) 
                {
                	double dblValue = Double.parseDouble(tokens[i]);
                	gazePos.getTimestamps().add(dblValue); 	
                }
                gazePositions.add(gazePos);
            }
        } 
        catch (Exception e) {
        	System.out.println("Error in CsvFileReader !!!");
            e.printStackTrace();
        } finally {
            try {
                fileReader.close();
            } catch (IOException e) {
            	System.out.println("Error while closing fileReader !!!");
                e.printStackTrace();
            }
        }

	}

	
	public void closeStream(){
		try {
			if(this.fileWriter!=null)
				this.fileWriter.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	public String findLastDir() throws IOException{
		
		Date today = new Date();		
		SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy_MM_dd");
		String dateDir = DATE_FORMAT.format(today);
		
		if(StringUtils.isNullOrEmpty(dateDir)){
			throw new IOException("Could not find date-directory");
		}
		
		String recDir = ABSOLUTE_PATH.concat(dateDir);
		
		if(!(new File(recDir)).isDirectory()){
			throw new IOException("RecDir is not a directory");			
		}
		
		recDir = recDir.concat("\\");
		
		File[] files = new File(recDir).listFiles();
		
		File lastDir = null;
		
		for(File file : files){
			if(file.isDirectory()){				
				lastDir = file;
			}			
		}
		
		if(lastDir == null){
			//lastDir = new File("000");
			throw new IOException("RecDirectory was not created");
		}
		 	
		
		return recDir.concat(lastDir.getName()).concat("\\");
	}
	
	 
}
