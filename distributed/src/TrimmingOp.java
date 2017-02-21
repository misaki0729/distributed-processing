import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfRect;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.highgui.Highgui;
import org.opencv.imgproc.Imgproc;

import com.twitter.chill.Base64.InputStream;

import backtype.storm.task.TopologyContext;
import nl.tno.stormcv.model.CVParticle;
import nl.tno.stormcv.model.Descriptor;
import nl.tno.stormcv.model.Feature;
import nl.tno.stormcv.model.Frame;
import nl.tno.stormcv.model.serializer.CVParticleSerializer;
import nl.tno.stormcv.model.serializer.FrameSerializer;
import nl.tno.stormcv.operation.ISingleInputOperation;
import nl.tno.stormcv.operation.OpenCVOp;
import nl.tno.stormcv.util.ImageUtils;
import nl.tno.stormcv.util.connector.ConnectorHolder;
import nl.tno.stormcv.util.connector.FileConnector;
import nl.tno.stormcv.util.connector.LocalFileConnector;

public class TrimmingOp extends OpenCVOp<Frame> implements ISingleInputOperation<Frame> {
  private static final long serialVersionUID = 5628467120758880353L;
  private FrameSerializer serializer = new FrameSerializer();
  private static Color[] colors = new Color[]{Color.RED, Color.BLUE, Color.GREEN, Color.PINK, Color.YELLOW, Color.CYAN, Color.MAGENTA};
  private String writeLocation;
  private ConnectorHolder connectorHolder;
  private boolean drawMetadata = false;
  //private Logger logger = LoggerFactory.getLogger(getClass());

  public TrimmingOp() {}
  public TrimmingOp destination(String location){
    this.writeLocation = location;
    return this;
  }

  public TrimmingOp drawMetadata(boolean bool){
    this.drawMetadata = bool;
    return this;
  }

  @SuppressWarnings("rawtypes")
  @Override
  public void prepare(Map stormConf, TopologyContext context) throws Exception {
    this.connectorHolder = new ConnectorHolder(stormConf);
  }

  @Override
  public void deactivate() {	}

  @Override
  public CVParticleSerializer<Frame> getSerializer() {
    return serializer;
  }

  @Override
  public List<Frame> execute(CVParticle particle) throws Exception {
    List<Frame> result = new ArrayList<Frame>();
    if(!(particle instanceof Frame)) return result;
    Frame sf = (Frame)particle;
    result.add(sf);
    BufferedImage image = sf.getImage();

    MatOfByte mob = new MatOfByte(sf.getImageBytes());
    Mat mat_image = Highgui.imdecode(mob, Highgui.CV_LOAD_IMAGE_COLOR);

    if(image == null) return result;

    int colorIndex = 0;
    for(Feature feature : sf.getFeatures()){
      //graphics.setColor(colors[colorIndex % colors.length]);
      for(Descriptor descr : feature.getSparseDescriptors()){
        Rectangle box = descr.getBoundingBox().getBounds();
        if(box.width == 0 ) box.width = 1;
        if(box.height == 0) box.height = 1;

        Rect roi = new Rect(box.x, box.y, box.width, box.height);
        Mat mat_output = new Mat(mat_image, roi);
        //graphics.draw(box);
        Highgui.imencode(".jpg", mat_output, mob);
        byte[] bytes = mob.toArray();
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        image = ImageIO.read(in);
      }
      colorIndex++;
    }


    sf.setImage(image);
    if(writeLocation != null){
      String destination = writeLocation + (writeLocation.endsWith("/") ? "" : "/") + "result.jpg";
      FileConnector fl = connectorHolder.getConnector(destination);
      if(fl != null){
        fl.moveTo(destination);
        if(fl instanceof LocalFileConnector){
          //ImageIO.write(image, "jpg", fl.getAsFile());
        }else{
          File tmpImage = File.createTempFile(""+destination.hashCode(), ".jpg");
          //ImageIO.write(image, "jpg", tmpImage);
          fl.copyFile(tmpImage, true);
        }
      }
    }
    return result;
  }
  @Override
  protected void prepareOpenCVOp(Map arg0, TopologyContext arg1)
      throws Exception {
    // TODO 自動生成されたメソッド・スタブ

  }

}
