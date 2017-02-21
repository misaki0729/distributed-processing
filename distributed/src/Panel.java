import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.swing.*;

import nl.tno.stormcv.StormCVConfig;
import nl.tno.stormcv.bolt.SingleInputBolt;
import nl.tno.stormcv.model.Frame;
import nl.tno.stormcv.operation.ROIExtractionOp;
import nl.tno.stormcv.spout.CVParticleSpout;

import org.opencv.core.Core;
import org.opencv.highgui.VideoCapture;
import org.opencv.highgui.Highgui;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.features2d.DescriptorExtractor;
import org.opencv.features2d.DescriptorMatcher;
import org.opencv.features2d.FeatureDetector;
import org.opencv.imgproc.Imgproc;

import com.ceph.rados.IoCTX;
import com.ceph.rados.Rados;

import backtype.storm.Config;
import backtype.storm.StormSubmitter;
import backtype.storm.topology.TopologyBuilder;

public class Panel extends JPanel{
  private static final long serialVersionUID = 1L;
  private BufferedImage image;

  private static StormCVConfig conf;
  public static Rados cluster;

  public Panel(){
    super();
  }

  private BufferedImage getimage(){
    return image;
  }

  private void setimage(BufferedImage newimage){
    image=newimage;
    return;
  }

  /**
   * Converts/writes a Mat into a BufferedImage.
   *
   * @param matrix Mat of type CV_8UC3 or CV_8UC1
   * @return BufferedImage of type TYPE_3BYTE_BGR or TYPE_BYTE_GRAY
   */
  public static BufferedImage matToBufferedImage(Mat matrix) {
    int cols = matrix.cols();
    int rows = matrix.rows();
    int elemSize = (int)matrix.elemSize();
    byte[] data = new byte[cols * rows * elemSize];
    int type;

    matrix.get(0, 0, data);
    switch (matrix.channels()) {
      case 1:
        type = BufferedImage.TYPE_BYTE_GRAY;
        break;
      case 3:
        type = BufferedImage.TYPE_3BYTE_BGR;
        // bgr to rgb
        byte b;
        for(int i=0; i < data.length; i=i+3) {
          b = data[i];
          data[i] = data[i+2];
          data[i+2] = b;
        }
        break;
      default:
        return null;
    }

    BufferedImage image2 = new BufferedImage(cols, rows, type);
    image2.getRaster().setDataElements(0, 0, cols, rows, data);
    return image2;
  }

  public void paintComponent(Graphics g){
    BufferedImage temp=getimage();
    if(temp!=null){
      g.drawImage(temp,10,10,temp.getWidth(),temp.getHeight(), this);
    }
  }

  public static void init() {
    conf = new StormCVConfig();
    // Macの場合
//    conf.put(StormCVConfig.STORMCV_OPENCV_LIB, "mac64_opencv_java248.dylib");
    // Raspberry Piの場合
    conf.put(StormCVConfig.STORMCV_OPENCV_LIB, "linux32_opencv_java248.so");
    conf.setNumWorkers(3);
    conf.setMaxSpoutPending(32);
    conf.put(StormCVConfig.STORMCV_FRAME_ENCODING, Frame.JPG_IMAGE);
    conf.put(Config.TOPOLOGY_ENABLE_MESSAGE_TIMEOUTS, true);
    conf.put(Config.TOPOLOGY_MESSAGE_TIMEOUT_SECS, 10);
    conf.put(StormCVConfig.STORMCV_SPOUT_FAULTTOLERANT, false);
    conf.put(StormCVConfig.STORMCV_CACHES_TIMEOUT_SEC, 30);

    try {
      cluster = new Rados("admin");
      File file = new File("/etc/ceph/ceph.conf");
      cluster.confReadFile(file);
      cluster.connect();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public static void buildTopology() {
    String userDir = System.getProperty("user.dir").replaceAll("\\\\", "/");

    TopologyBuilder builder = new TopologyBuilder();
    builder.setSpout("spout", new CVParticleSpout(new TakingPhotoFetcher().sleep(100)), 1);
//    builder.setBolt("scale", new SingleInputBolt(new ScaleImageOp(0.5f)), 1).shuffleGrouping("spout");
//    builder.setBolt("grayscale", new SingleInputBolt(new GrayscaleOp()), 1).shuffleGrouping("spout");
    builder.setBolt("face_detect", new SingleInputBolt(
        new DetectFacesOp("face","lbpcascade_frontalface.xml")
        .outputFrame(true)
        ), 1)
        .shuffleGrouping("spout");
    builder.setBolt("face_extraction", new SingleInputBolt(
        new ROIExtractionOp("face")
      ), 1)
      .shuffleGrouping("face_detect");
    builder.setBolt("drawer", new SingleInputBolt(new TrimmingOp().destination("file://" + userDir + "/output/")), 1)
    .shuffleGrouping("face_extraction");

    List<String> files_matches = new ArrayList<>();
    builder.setBolt("match", new SingleInputBolt(new FeatureMatcherOp(files_matches, 3, 0.5f)
    .descriptor(DescriptorExtractor.BRISK)
    .detector(FeatureDetector.DENSE)
    .matcher(DescriptorMatcher.BRUTEFORCE_HAMMING)), 1).shuffleGrouping("drawer");


    try {
      // run in local mode
//      LocalCluster localCluster = new LocalCluster();
//      localCluster.submitTopology( "facedetection", conf, builder.createTopology() );
//      Utils.sleep(300*1000); // run for some time and then kill the topology
//      localCluster.shutdown();
//      System.exit(1);

      // run on a storm cluster
       StormSubmitter.submitTopology("facedetection", conf, builder.createTopology());
    } catch (Exception e){
      e.printStackTrace();
    }
  }

  public static void main(String arg[]){
    // Load the native library.
    System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
    JFrame frame = new JFrame("BasicPanel");
    frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    frame.setSize(400,400);
    Panel panel = new Panel();
    frame.setContentPane(panel);
    frame.setVisible(true);
    Mat webcam_image=new Mat();
    BufferedImage temp;
    VideoCapture capture =new VideoCapture(0);
    init();

    new Thread(new Runnable() {
      @Override
      public void run() {
        buildTopology();
      }
    }).start();

    if( capture.isOpened())
    {
      while( true )
      {
        try {
          Thread.sleep(100);
        } catch (Exception e) {
          e.printStackTrace();
        }

        capture.read(webcam_image);
        if( !webcam_image.empty() )
        {
          Imgproc.resize(webcam_image, webcam_image, new Size(webcam_image.size().width*0.5,webcam_image.size().height*0.5));
          Highgui.imwrite(String.format("./resources/webcam.jpg"), webcam_image);

          frame.setSize(webcam_image.width()+40,webcam_image.height()+60);
          temp=matToBufferedImage(webcam_image);
          panel.setimage(temp);

          panel.repaint();

          try {
            IoCTX io = cluster.ioCtxCreate("data");

            FileSystem fs = FileSystems.getDefault();
            Path p = fs.getPath(String.format("./resources/webcam.jpg"));
            byte[] bytes = Files.readAllBytes(p);
            int length = bytes.length;
            String char_len = String.format("%10d", length);
            String oid = String.format("webcam_length");
            io.write(oid, char_len);
            oid = String.format("webcam");
            io.write(oid, bytes, 0);
          } catch (Exception e) {
        	  e.printStackTrace();
          }
        } else {
          System.out.println(" --(!) No captured frame -- ");
        }
      }
    }
    return;
  }
}


