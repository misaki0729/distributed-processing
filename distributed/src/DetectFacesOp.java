import java.awt.Rectangle;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfRect;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.highgui.Highgui;
import org.opencv.objdetect.CascadeClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import backtype.storm.task.TopologyContext;
import backtype.storm.utils.Utils;
import nl.tno.stormcv.model.Descriptor;
import nl.tno.stormcv.model.Feature;
import nl.tno.stormcv.model.Frame;
import nl.tno.stormcv.util.NativeUtils;
import nl.tno.stormcv.model.*;
import nl.tno.stormcv.model.serializer.*;
import nl.tno.stormcv.operation.ISingleInputOperation;
import nl.tno.stormcv.operation.OpenCVOp;

public class DetectFacesOp extends OpenCVOp<CVParticle> implements ISingleInputOperation<CVParticle> {

  private static final long serialVersionUID = 1672563520721443005L;
  private Logger logger = LoggerFactory.getLogger(DetectFacesOp.class);
  private String name;
  private CascadeClassifier haarDetector;
  private String haarXML;
  private int[] minSize = new int[]{0,0};
  private int[] maxSize = new int[]{1000, 1000};
  private int minNeighbors = 3;
  private int flags = 0;
  private float scaleFactor = 1.1f;
  private boolean outputFrame = false;
  @SuppressWarnings("rawtypes")
  private CVParticleSerializer serializer = new FeatureSerializer();

  public DetectFacesOp(String name, String haarXML){
    this.name = name;
    this.haarXML = haarXML;
  }

  public DetectFacesOp minSize(int w, int h){
    this.minSize = new int[]{w, h};
    return this;
  }

  public DetectFacesOp maxSize(int w, int h){
    this.maxSize = new int[]{w, h};
    return this;
  }

  public DetectFacesOp minNeighbors(int number){
    this.minNeighbors = number;
    return this;
  }

  public DetectFacesOp flags(int f){
    this.flags = f;
    return this;
  }

  public DetectFacesOp scale(float factor){
    this.scaleFactor  = factor;
    return this;
  }

  public DetectFacesOp outputFrame(boolean frame){
    this.outputFrame = frame;
    if(outputFrame){
      this.serializer = new FrameSerializer();
    }else{
      this.serializer = new FeatureSerializer();
    }
    return this;
  }

  @SuppressWarnings("rawtypes")
  @Override
  protected void prepareOpenCVOp(Map stormConf, TopologyContext context) throws Exception {
    try {
      if(haarXML.charAt(0) != '/') haarXML = "/"+haarXML;
      File cascadeFile = NativeUtils.extractTmpFileFromJar(haarXML, true);
      haarDetector = new CascadeClassifier(cascadeFile.getAbsolutePath());
      Utils.sleep(100); // make sure the classifier has loaded before removing the tmp xml file
      if(!cascadeFile.delete()) cascadeFile.deleteOnExit();
    } catch (Exception e) {
      logger.error("Unable to instantiate SimpleFaceDetectionBolt due to: "+e.getMessage(), e);
      throw e;
    }
  }

  @Override
  public List<CVParticle> execute(CVParticle input) throws Exception {
    //logger.info("Detect( ´∀｀)bｸﾞｯ!");
    ArrayList<CVParticle> result = new ArrayList<CVParticle>();
    Frame frame = (Frame)input;
    if(frame.getImageType().equals(Frame.NO_IMAGE)) {
      logger.info("no image.");
      return result;
    }

    MatOfByte mob = new MatOfByte(frame.getImageBytes());
    Mat image = Highgui.imdecode(mob, Highgui.CV_LOAD_IMAGE_COLOR);

    //logger.info("Detect( ´∀｀)bｸﾞｯ!");
    if (image == null) return result;

    //logger.info("Detect( ´∀｀)bｸﾞｯ!");
    /*
    mob = new MatOfByte();
    Highgui.imencode(".png", image, mob);
    BufferedImage bi = ImageUtils.bytesToImage(mob.toArray());
    ImageIO.write(bi, "png", new File("testOutput/"+sf.getStreamId()+"_"+sf.getSequenceNr()+".png"));
    */

    MatOfRect haarDetections = new MatOfRect();
    haarDetector.detectMultiScale(image, haarDetections);
    if (haarDetections.toArray().length > 0) {
      logger.info("Faces " + haarDetections.toArray().length + " faces find.");
    }
    ArrayList<Descriptor> descriptors = new ArrayList<Descriptor>();
    for(Rect rect : haarDetections.toArray()){
      Rectangle box = new Rectangle(rect.x, rect.y, rect.width, rect.height);
      descriptors.add(new Descriptor(input.getStreamId(), input.getSequenceNr(), box, 0, new float[0]));
    }

    Feature feature = new Feature(input.getStreamId(), input.getSequenceNr(), name, 0, descriptors, null);
    if(outputFrame){
      frame.getFeatures().add(feature);
      result.add(frame);
    }else{
      result.add(feature);
    }
    return result;
  }

  @Override
  public void deactivate() {
    haarDetector = null;
  }

  @SuppressWarnings("unchecked")
  @Override
  public CVParticleSerializer<CVParticle> getSerializer() {
    return this.serializer;
  }

}