package eu.opends.drivesense;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import com.jme3.asset.AssetManager;
import com.jme3.collision.CollisionResult;
import com.jme3.collision.CollisionResults;
import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
import com.jme3.input.InputManager;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Ray;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Sphere;

import eu.opends.car.Car;
import eu.opends.drivesense.domain.GazeData;
import eu.opends.drivesense.extensions.UDPClient;
import eu.opends.drivesense.extensions.Vector2d;
import eu.opends.drivingTask.settings.SettingsLoader;
import eu.opends.drivingTask.settings.SettingsLoader.Setting;
import eu.opends.eyetracker.DataLogger;
import eu.opends.eyetracker.Fixation;
import eu.opends.main.SimulationDefaults;
import eu.opends.main.Simulator;
import eu.opends.tools.PanelCenter;
import eu.opends.tools.Util;
import eu.opends.traffic.PhysicalTraffic;
import eu.opends.traffic.TrafficCar;
import eu.opends.traffic.TrafficObject;
import eu.opends.trigger.TriggerCenter;

public class EyeTrackerProc
{	
	public int udpPort = 2010;
	public int packetSize = 4048;
	
	// mean average of gaze position over last x values
	private int smoothingFactor = 10;
	
	private boolean showCrossHairs = true;
	private ColorRGBA crossHairsColor = ColorRGBA.White;
	private float scalingFactor = 2;
	
	private boolean showGazeSphere = true;
	private ColorRGBA sphereColor = ColorRGBA.Red;
	
	private ColorMode colorMode = ColorMode.None;
	private ColorRGBA glowColor = ColorRGBA.Orange;
	
	private boolean showWarningFrame = true;
	// gaze off screen warning will be raised after x milliseconds
	private int warningThreshold = 3000;
	// flashing interval (ms) of warning frame
	private int flashingInterval = 500;
 
	private UDPClient udpClient; 
	private DataLogger dataLogger;
	private Vector2f screenPos;
	private Simulator sim;
	private Node sceneNode;
	private Camera cam;
	private AssetManager assetManager;
	private Geometry gazeSphere;
	private BitmapText crosshairs;
	private LinkedList<Vector2f> gazeStorage = new LinkedList<Vector2f>();
	private long lastScreenGaze = 0;  

	
	private EyeTrackerIO eyeTrackerIO;

	//private HMIWebSocketClient clientEndPointer; 
	
	private enum ColorMode
	{
		None, VehiclesOnly, All;
	}

	
	public EyeTrackerProc(Simulator sim)
	{
		this.sim = sim;
		this.sceneNode = sim.getSceneNode();
		this.cam = sim.getCamera();
		this.assetManager = sim.getAssetManager();
		
		initSettings();
		
		// init gaze pos
		screenPos = new Vector2f(sim.getSettings().getWidth() / 2f, sim.getSettings().getHeight() / 2f);
			
		// a "+" in the middle of the screen to help aiming
		BitmapFont guiFont = assetManager.loadFont("Interface/Fonts/Default.fnt");
		crosshairs = new BitmapText(guiFont, false);
		crosshairs.setSize(guiFont.getCharSet().getRenderedSize() * scalingFactor);
		crosshairs.setText("+");
		crosshairs.setColor(crossHairsColor);
			
		if(showCrossHairs)
			sim.getGuiNode().attachChild(crosshairs);

		// init a colored sphere to mark the target
		Sphere sphere = new Sphere(30, 30, 0.2f);
		gazeSphere = new Geometry("gazeSphere", sphere);
		Material sphere_mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
		sphere_mat.setColor("Color", sphereColor);
		gazeSphere.setMaterial(sphere_mat);

		// create log-thread
		udpClient = new UDPClient(sim, udpPort, packetSize);
		udpClient.start();  
		
		eyeTrackerIO = new EyeTrackerIO();
		
		dataLogger = new DataLogger();
	}

	private void unsetFlyCam() 
	{
		InputManager inputManager = sim.getInputManager();
		
		// remove FlyCam mapping concerning arrow keys, mouse movement and joystick
		// which interferes the computation of the eye gaze ray
		if(inputManager.hasMapping("FLYCAM_Left"))
			inputManager.deleteMapping("FLYCAM_Left");
		
		if(inputManager.hasMapping("FLYCAM_Right"))
			inputManager.deleteMapping("FLYCAM_Right");
		
		if(inputManager.hasMapping("FLYCAM_Up"))
			inputManager.deleteMapping("FLYCAM_Up");
		
		if(inputManager.hasMapping("FLYCAM_Down"))
			inputManager.deleteMapping("FLYCAM_Down");
		
		if(inputManager.hasMapping("FLYCAM_ZoomIn"))
			inputManager.deleteMapping("FLYCAM_ZoomIn");
		
		if(inputManager.hasMapping("FLYCAM_ZoomOut"))
			inputManager.deleteMapping("FLYCAM_ZoomOut");
		
		if(inputManager.hasMapping("FLYCAM_RotateDrag"))
			inputManager.deleteMapping("FLYCAM_RotateDrag");
		
		if(inputManager.hasMapping("FLYCAM_StrafeLeft"))
			inputManager.deleteMapping("FLYCAM_StrafeLeft");
		
		if(inputManager.hasMapping("FLYCAM_StrafeRight"))
			inputManager.deleteMapping("FLYCAM_StrafeRight");
		
		if(inputManager.hasMapping("FLYCAM_Forward"))
			inputManager.deleteMapping("FLYCAM_Forward");
		
		if(inputManager.hasMapping("FLYCAM_Backward"))
			inputManager.deleteMapping("FLYCAM_Backward");
		
		if(inputManager.hasMapping("FLYCAM_Rise"))
			inputManager.deleteMapping("FLYCAM_Rise");
		
		if(inputManager.hasMapping("FLYCAM_Lower"))
			inputManager.deleteMapping("FLYCAM_Lower");
		
		if(inputManager.hasMapping("FLYCAM_InvertY"))
			inputManager.deleteMapping("FLYCAM_InvertY");
	}


	private void initSettings() 
	{
		SettingsLoader settingsLoader = Simulator.getDrivingTask().getSettingsLoader();
		
		udpPort = settingsLoader.getSetting(Setting.Eyetracker_port, SimulationDefaults.Eyetracker_port);
		smoothingFactor = settingsLoader.getSetting(Setting.Eyetracker_smoothingFactor, SimulationDefaults.Eyetracker_smoothingFactor);
		showCrossHairs = settingsLoader.getSetting(Setting.Eyetracker_crossHairs_show, SimulationDefaults.Eyetracker_crossHairs_show);
		
		String crossHairsColorString = settingsLoader.getSetting(Setting.Eyetracker_crossHairs_color, SimulationDefaults.Eyetracker_crossHairs_color);
		try {
			crossHairsColor = (ColorRGBA) ColorRGBA.class.getField(crossHairsColorString).get(new ColorRGBA());
		} catch (Exception e) {}

		scalingFactor = settingsLoader.getSetting(Setting.Eyetracker_crossHairs_scalingFactor, SimulationDefaults.Eyetracker_crossHairs_scalingFactor);
		
		showGazeSphere = settingsLoader.getSetting(Setting.Eyetracker_gazeSphere_show, SimulationDefaults.Eyetracker_gazeSphere_show);
		
		String sphereColorString = settingsLoader.getSetting(Setting.Eyetracker_gazeSphere_color, SimulationDefaults.Eyetracker_gazeSphere_color);
		try {
			sphereColor = (ColorRGBA) ColorRGBA.class.getField(sphereColorString).get(new ColorRGBA());
		} catch (Exception e) {}
		
		String colorModeString = settingsLoader.getSetting(Setting.Eyetracker_highlightObjects_mode, SimulationDefaults.Eyetracker_highlightObjects_mode);
		try {
			colorMode = ColorMode.valueOf(colorModeString);
		} catch (Exception e) {}
		
		String glowColorString = settingsLoader.getSetting(Setting.Eyetracker_highlightObjects_color, SimulationDefaults.Eyetracker_highlightObjects_color);
		try {
			glowColor = (ColorRGBA) ColorRGBA.class.getField(glowColorString).get(new ColorRGBA());
		} catch (Exception e) {}
		
		showWarningFrame = settingsLoader.getSetting(Setting.Eyetracker_warningFrame_show, SimulationDefaults.Eyetracker_warningFrame_show);

		warningThreshold = settingsLoader.getSetting(Setting.Eyetracker_warningFrame_threshold, SimulationDefaults.Eyetracker_warningFrame_threshold);

		flashingInterval = settingsLoader.getSetting(Setting.Eyetracker_warningFrame_flashingInterval, SimulationDefaults.Eyetracker_warningFrame_flashingInterval);

		/*		
		System.err.println("udpPort: " + udpPort + " smoothingFactor: " + smoothingFactor
				 + " showCrossHairs: " + showCrossHairs + " crossHairsColor: " + crossHairsColor
				 + " scalingFactor: " + scalingFactor + " showGazeSphere: " + showGazeSphere
				 + " sphereColor: " + sphereColor + " colorMode: " + colorMode
				 + " glowColor: " + glowColor + " showWarningFrame: " + showWarningFrame
				 + " warningThreshold: " + warningThreshold + " flashingInterval: " + flashingInterval);
		*/
	}	
	
	private Vector2f convertNormPos(String normPos){
		normPos.replace("(", "").replace(")", "");		
		String[] arr = normPos.split(",");
		
		float x = Float.parseFloat(arr[0]);
	    float y = Float.parseFloat(arr[1]);
		
		return new Vector2f(x,y);
	}
	
	
	public void update()
	{
		unsetFlyCam();
		JSONParser parser = new JSONParser();
		String datagramm = udpClient.getDatagram();		
		
		try {
			
			if(datagramm == null) {
				System.err.println("Did not receive the datagramm");
				return;
			}
			
			JSONObject  jsonObj = (JSONObject ) parser.parse(datagramm);
			
			//Set the srf
			String srf = (String) jsonObj.get("srf");
			JSONArray jsonArray = (JSONArray)jsonObj.get("data");
						
			int nSize = jsonArray.size();
			
			if(jsonArray==null || nSize == 0)
			{
				System.err.println("No data as JSONArray retrieved");
				return;
			}
						
			GazeData[] gazesData = new GazeData[nSize];
			
			double raw_x = 0f, 
				   raw_y = 0f;
						
			for(int i=0; i<nSize;i++)
			{
				JSONObject jsonGaze = (JSONObject) jsonArray.get(i);
				JSONArray dataArr = (JSONArray)jsonGaze.get("norm_pos");
				//Retrieve all data from  
				double confidence = (double) jsonGaze.get("confidence");
				double timestamp = (double) jsonGaze.get("timestamp");				
				double normPosX = (double)dataArr.get(0);
				double normPosY = (double)dataArr.get(1);		
				Vector2d normPos = new Vector2d(normPosX, normPosY);							
				gazesData[i] = new GazeData(1,timestamp,normPos,confidence,srf);
				
				System.out.println(normPos);
				
				raw_x += normPosX;
				raw_y += normPosY;
			}
			
			raw_x = Vector2d.round(raw_x/nSize,2);
			raw_y = Vector2d.round(raw_y/nSize,2); 
			
			Vector2d raw_pos = new Vector2d(raw_x,raw_y);
			
			//Smooth the gaze
			double smoothX = 0.5f,
				   smoothY = 0.5f;
			
			if(srf!=null && srf.equals("screen"))
			{								
				smoothX += 0.35f * (raw_x - smoothX);
				smoothY += 0.35f * (raw_x - smoothY);
				
				double x = Math.min(1, Math.max(0, smoothX));
				double y = Math.min(1, Math.max(0, 1 - smoothY)); //inverting y so it shows up correctly on screen
				
				x *= sim.getSettings().getWidth();
				y *= sim.getSettings().getHeight();
				
				raw_pos.setX(x);
				raw_pos.setY(y);
				
				String aoi = this.identifyAOI(raw_pos.getFloatX(), raw_pos.getFloatY());
				
				for(GazeData gazeData : gazesData){
					gazeData.setAoi(aoi);
					//eyeTrackerIO.writeGazeToCSV(gazeData);
				}
				
			}else{
				for(GazeData gazeData : gazesData){ 
					//this.eyeTrackerIO.writeGazeToCSV(gazeData);
				}
			}
			
 
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} 
	}
	
	public String identifyAOI(float raw_x, float raw_y)
	{
		//screenPos = processGazeLIFO(new Vector2f(raw_x,raw_y));
		
		screenPos.setX(raw_x);
		screenPos.setY(raw_y);

//		if(showWarningFrame)
//			checkForOffScreenGaze();
			
		// set cross hairs
		crosshairs.setLocalTranslation(screenPos.getX() - crosshairs.getLineWidth()/2f,
			screenPos.getY() + crosshairs.getLineHeight()/2f, 0);

		// reset previous position of colored sphere
		sceneNode.detachChild(gazeSphere);

		// reset collision results list
		CollisionResults results = new CollisionResults();
			
		// compute the world position on the far plane
		Vector3f worldPosFar = cam.getWorldCoordinates(screenPos, 1);
		
		// compute the world position on the near plane
		Vector3f worldPosNear = cam.getWorldCoordinates(screenPos, 0);
			
		// compute direction towards target (from camera)
		Vector3f direction = worldPosFar.subtract(worldPosNear);

		// normalize direction vector
		direction.normalizeLocal();

		// aim a ray from the camera towards the target
		Ray ray = new Ray(worldPosNear, direction);

		// collect intersections between ray and scene elements in results list.
		sceneNode.collideWith(ray, results);
		
		if(colorMode != ColorMode.None)
			uncolor();
			
		// use the results (we mark the hit object)
		if (results.size() > 0) 
		{
			//The closest collision point is what was truly hit
			CollisionResult closest = results.getClosestCollision();
			
			if(showGazeSphere)
			{
				// mark the hit with a colored sphere
				gazeSphere.setLocalTranslation(closest.getContactPoint());
				sceneNode.attachChild(gazeSphere);
			}
			
			//Useful
			Geometry geometry = closest.getGeometry();
			
			String path = Util.getPath(geometry); 

			colorGeometry(geometry);
			
			return path;
		}
		
		return null;
	}


	private Vector2f processGazeLIFO(Vector2f gazePos) 
	{		
    	Vector2f sum = new Vector2f(0,0);
    	
    	gazeStorage.addLast(gazePos);

        for (Vector2f vector : gazeStorage)
        	sum.addLocal(vector);
        
        if(gazeStorage.size() >= smoothingFactor)
        	gazeStorage.removeFirst();

        return sum.divide(smoothingFactor);
	}
	
	private Fixation previousFixation = new Fixation(null, null, null);
	private void reportFixation(CollisionResult closest) 
	{
		// if gazing at new object
		if(!closest.getGeometry().equals(previousFixation.getGeometry()))
		{
			// write previous fixation to log file
			previousFixation.writeToLog();
			
			// create "new fixation"
			previousFixation = new Fixation(closest.getGeometry(), closest.getContactPoint(), screenPos);
		}
	}
	
	public void consoleLog(CollisionResult closest, Geometry geometry, Vector2f screenCoordinate)
	{
		if(geometry != null)
		{
			long duration = System.currentTimeMillis(); 
			String path = Util.getPath(geometry);
			System.err.println(": " + path + ", " 
			+ closest.getContactPoint() + ", " + screenCoordinate + ", " + duration + " ms");
		}
	}	
	
	private void reportNoFixation() 
	{
		// write previous fixation to log file
		previousFixation.writeToLog();

		// create "no fixation"
		previousFixation = new Fixation(null, null, null);
	}
	
	private void checkForOffScreenGaze() 
	{
		if(screenPos.getX() < 0 || screenPos.getX() > sim.getSettings().getWidth() ||
		   screenPos.getY() < 0 || screenPos.getY() > sim.getSettings().getHeight())
		{
			// time gaze is off screen
			long offTime = System.currentTimeMillis() - lastScreenGaze;
			
			// if threshold exceeded -> warning
			if(lastScreenGaze != 0 && (offTime > warningThreshold))
				PanelCenter.showWarningFrame(true, flashingInterval);
				//System.err.println("OUT OF SCREEN: " + offTime);
		}		
		else
		{
			// reset timer when in screen again
			lastScreenGaze = System.currentTimeMillis();
			PanelCenter.showWarningFrame(false);
		}
	}
	

	private void uncolor()
	{
		// uncolor all objects
		for(Geometry g : Util.getAllGeometries(sceneNode))
		{
			try{
			
				g.getMaterial().setColor("GlowColor", ColorRGBA.Black);
			
			} 
			catch (IllegalArgumentException e) 
			{
			}
		}
	}
	
	
	private void colorGeometry(Geometry geometry) 
	{
		if(colorMode != ColorMode.None)
		{		
			if(colorMode == ColorMode.VehiclesOnly)
			{
				// color hit traffic and multi-driver vehicles only
				colorTrafficVehicles(geometry);	
				colorMultiDriverVehicles(geometry);
			}
			else
			{
				// color all hit objects
				geometry.getMaterial().setColor("GlowColor", glowColor);
			}
		}
	}


	private void colorTrafficVehicles(Geometry geometry) 
	{
		for(TrafficObject c : PhysicalTraffic.getTrafficObjectList())
		{
			if(c instanceof TrafficCar)
			{
				boolean colorCar = false;
				
				// all geometries of current traffic car
				List<Geometry> carGeometries = Util.getAllGeometries(((Car) c).getCarNode());
				for(Geometry g : carGeometries)
				{
					// if hit geometry is part of current car, color whole car
					if(g.equals(geometry))
					{
						colorCar = true;
						break;
					}
				}
				
				if(colorCar)
				{
					// color all geometries of current car
					for(Geometry g : carGeometries)
						g.getMaterial().setColor("GlowColor", glowColor);
					
					break;
				}
			}
		}
	}


	private void colorMultiDriverVehicles(Geometry geometry)
	{
		ArrayList<String> registeredVehicles = new ArrayList<String>();
		
		if(sim.getMultiDriverClient() != null)
			registeredVehicles = sim.getMultiDriverClient().getRegisteredVehicles();
		
		for(String vehicleID : registeredVehicles)
		{
			Node carNode = (Node) sceneNode.getChild(vehicleID);
			
			if(carNode != null)
			{
				boolean colorCar = false;
			
				// all geometries of current multi-driver car
				List<Geometry> carGeometries = Util.getAllGeometries(carNode);
				for(Geometry g : carGeometries)
				{
					// if hit geometry is part of current car, color whole car
					if(g.equals(geometry))
					{
						colorCar = true;
						break;
					}
				}
			
				if(colorCar)
				{
					// color all geometries of current car
					for(Geometry g : carGeometries)
						g.getMaterial().setColor("GlowColor", glowColor);
				
					break;
				}
			}
		}
	}


	public void close()
	{
		// stop UDP thread
		udpClient.requestStop();
		//dataLogger.close();
	}
}
