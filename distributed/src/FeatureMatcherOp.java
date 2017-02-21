import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfDMatch;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.features2d.DMatch;
import org.opencv.features2d.DescriptorExtractor;
import org.opencv.features2d.DescriptorMatcher;
import org.opencv.features2d.FeatureDetector;
import org.opencv.highgui.Highgui;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ceph.rados.IoCTX;

import backtype.storm.task.TopologyContext;
import nl.tno.stormcv.model.CVParticle;
import nl.tno.stormcv.model.Frame;
import nl.tno.stormcv.model.serializer.CVParticleSerializer;
import nl.tno.stormcv.model.serializer.FrameSerializer;
import nl.tno.stormcv.operation.ISingleInputOperation;
import nl.tno.stormcv.operation.OpenCVOp;
import nl.tno.stormcv.util.ImageUtils;
import nl.tno.stormcv.util.connector.ConnectorHolder;
import nl.tno.stormcv.util.connector.FileConnector;
import nl.tno.stormcv.util.connector.LocalFileConnector;

public class FeatureMatcherOp extends OpenCVOp<Frame> implements ISingleInputOperation<Frame> {

  private static final long serialVersionUID = -7759756124507057013L;
  private Logger logger = LoggerFactory.getLogger(getClass());
  private static final String[] ext = new String[]{".jpg", ".JPG", ".jpeg", ".JPEG", ".png", ".PNG", ".gif", ".GIF", ".bmp", ".BMP"};
  private ConnectorHolder connectorHolder;
  private int detectorType = FeatureDetector.SIFT;
  private int descriptorType = DescriptorExtractor.SIFT;
  private int matcherType = DescriptorMatcher.BRUTEFORCE;
  private int minStrongMatches = 3;
  private float minStrongMatchDist = 0.5f;

  private DescriptorMatcher matcher;
  private HashMap<Mat, String> prototypes;
  private List<String> protoLocations;
  private List<Mat> descriptor;
  private List<BufferedImage> bufferedImages;

  public FeatureMatcherOp(List<String> prototypes, int minStrongMatches, float minStrongMatchDist){
    this.protoLocations = prototypes;
    this.minStrongMatches = minStrongMatches;
    this.minStrongMatchDist = minStrongMatchDist;
  }

  public FeatureMatcherOp detector(int type){
    this.detectorType = type;
    return this;
  }

  public FeatureMatcherOp descriptor(int type){
    this.descriptorType = type;
    return this;
  }

  public FeatureMatcherOp matcher(int type){
    this.matcherType = type;
    return this;
  }

  @SuppressWarnings("rawtypes")
  @Override
  protected void prepareOpenCVOp(Map conf, TopologyContext context) throws Exception {
    this.connectorHolder = new ConnectorHolder(conf);
    matcher = DescriptorMatcher.create( matcherType );
    prototypes = new HashMap<Mat, String>();

    bufferedImages = new ArrayList<>();
    for(String location : protoLocations){
      FileConnector fc = connectorHolder.getConnector(location);
      fc.setExtensions(ext);
      fc.moveTo(location);
      List<String> images = fc.list();
      for(String img : images){
        fc.moveTo(img);
        File imageFile = fc.getAsFile();
        bufferedImages.add(ImageIO.read(imageFile));
        Mat proto = calculateDescriptors(ImageIO.read(imageFile));
        prototypes.put(proto, img.substring(img.lastIndexOf('/')+1));
        logger.info("Prototype "+img+" loaded and prepared for matching");
        if(!(fc instanceof LocalFileConnector)) imageFile.delete();
      }
    }
  }

  @Override
  public void deactivate() {
    prototypes.clear();
  }

  @Override
  public CVParticleSerializer<Frame> getSerializer() {
    return new FrameSerializer();
  }

  @Override
  public List<Frame> execute(CVParticle particle) throws Exception {
    List<Frame> result = new ArrayList<Frame>();
    if(!(particle instanceof Frame)) return result;

    Frame sf = (Frame)particle;
    BufferedImage image = sf.getImage();

    MatOfByte mob = new MatOfByte(sf.getImageBytes());
    Mat face = Highgui.imdecode(mob, Highgui.CV_LOAD_IMAGE_COLOR);

    float min_dist = 100000;
    try {
      IoCTX io = Panel.cluster.ioCtxCreate("data");

      FeatureDetector detector = FeatureDetector.create(FeatureDetector.DENSE);

      MatOfKeyPoint keyPoint1 = new MatOfKeyPoint();
      detector.detect(face, keyPoint1);

      DescriptorExtractor extractor = DescriptorExtractor.create(DescriptorExtractor.BRISK);
      Mat descriptors1 = new Mat();
      extractor.compute(face, keyPoint1, descriptors1);

      DescriptorMatcher matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING);

      for (int i = 2; i <= 12; i++) {
        String oid = String.format("misaki%02d_length", i);

        byte[] bLength = new byte[10];
        io.read(oid, 10, 0, bLength);
        String sLength = new String(bLength, "UTF-8").trim();
        int length = Integer.parseInt(sLength);

        oid = String.format("misaki%02d", i);
        byte[] imgByte = new byte[length];
        io.read(oid, length, 0, imgByte);

        Mat img = Highgui.imdecode(new MatOfByte(imgByte), Highgui.CV_LOAD_IMAGE_UNCHANGED);
        Mat grayImg = new Mat(img.rows(), img.cols(), img.type());
        Imgproc.cvtColor(img, grayImg, Imgproc.COLOR_BGR2GRAY);

        MatOfKeyPoint keyPoint2 = new MatOfKeyPoint();
        detector.detect(grayImg, keyPoint2);
        Mat descriptors2 = new Mat();
        extractor.compute(grayImg, keyPoint2, descriptors2);

        MatOfDMatch matchs = new MatOfDMatch();
        matcher.match(descriptors1, descriptors2, matchs);

        DMatch[] array = matchs.toArray();
        float[] distances = new float[array.length];
        for (int j = 0; j < array.length; j++) {
          distances[j] = array[j].distance;
        }
        java.util.Arrays.sort(distances);

        if (distances.length > 0 && min_dist > distances[0]) {
          min_dist = distances[0];
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
    }

    logger.info("min_dist: " + min_dist);

    return result;
  }

  private Mat calculateDescriptors(BufferedImage input) throws IOException{
    byte[] buffer;
    buffer = ImageUtils.imageToBytes(input, "png");
    return calculateDescriptors(buffer);
  }

  private Mat calculateDescriptors(byte[] buffer) throws IOException{
    MatOfByte mob = new MatOfByte(buffer);
    Mat image = Highgui.imdecode(mob, Highgui.CV_LOAD_IMAGE_ANYCOLOR);

    FeatureDetector siftDetector = FeatureDetector.create(detectorType);
    MatOfKeyPoint mokp = new MatOfKeyPoint();
    siftDetector.detect(image, mokp);

    Mat descriptors = new Mat();
    DescriptorExtractor extractor = DescriptorExtractor.create(descriptorType);
    extractor.compute(image, mokp, descriptors);
    return descriptors;
  }

}
