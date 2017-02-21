import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import com.ceph.rados.IoCTX;
import com.ceph.rados.Rados;

import backtype.storm.task.TopologyContext;
import backtype.storm.utils.Utils;
import nl.tno.stormcv.StormCVConfig;
import nl.tno.stormcv.fetcher.IFetcher;
import nl.tno.stormcv.model.Frame;
import nl.tno.stormcv.model.serializer.CVParticleSerializer;
import nl.tno.stormcv.model.serializer.FrameSerializer;


public class TakingPhotoFetcher implements IFetcher<Frame> {
  private int sleep = 40;
  private String imageType;
  private LinkedBlockingQueue<Frame> frameQueue;
  private List<ImageReader> readers;
  private Logger logger = LoggerFactory.getLogger(getClass());
  public static Rados cluster;

  public TakingPhotoFetcher sleep(int ms) {
    this.sleep = ms;
    return this;
  }

  @Override
  public void prepare(Map stormConf, TopologyContext context) throws Exception {
    frameQueue = new LinkedBlockingQueue<>();

    if(stormConf.containsKey(StormCVConfig.STORMCV_FRAME_ENCODING)){
      imageType = (String)stormConf.get(StormCVConfig.STORMCV_FRAME_ENCODING);
    }
    readers = new ArrayList<>();

    cluster = new Rados("admin");
    File file = new File("/etc/ceph/ceph.conf");
    cluster.confReadFile(file);
    cluster.connect();
  }

  @Override
  public CVParticleSerializer<Frame> getSerializer() {
    return new FrameSerializer();
  }

  @Override
  public void activate() {
    ImageReader ir = new ImageReader(sleep, frameQueue);
    new Thread(ir).start();
    readers.add(ir);
  }

  @Override
  public void deactivate() {
    for (ImageReader reader: readers) {
      reader.stop();
    }
    readers.clear();
    frameQueue.clear();
  }

  @Override
  public Frame fetchData() {
    return frameQueue.poll();
  }

  private class ImageReader implements Runnable {
    private Logger logger = LoggerFactory.getLogger(getClass());
    LinkedBlockingQueue<Frame> frameQueue;
    private int sleep;
    private int sequenceNr;
    private boolean running = true;

    BufferedImage temp;

    public ImageReader(int sleep, LinkedBlockingQueue<Frame> frameQueue) {
      this.sleep = sleep;
      this.frameQueue = frameQueue;
    }

    @Override
    public void run() {
      while (running) {
        try {
          IoCTX io = cluster.ioCtxCreate("data");

          //logger.info("(´・ω・`");

          String oid = String.format("webcam_length");

          byte[] bLength = new byte[10];
          io.read(oid, 10, 0, bLength);
          String sLength = new String(bLength, "UTF-8").trim();
          int length = Integer.parseInt(sLength);

          oid = String.format("webcam");
          byte[] imgByte = new byte[length];
          io.read(oid, length, 0, imgByte);

          //logger.info("(´・ω・`)");

//            Imgproc.resize(webcam_image, webcam_image, new Size(webcam_image.size().width*0.5,webcam_image.size().height*0.5));
//            Highgui.imwrite(String.format("./resources/webcam.jpg"), webcam_image);
          Frame frame = new Frame("cam", sequenceNr, imageType, imgByte, System.currentTimeMillis(), new Rectangle(640, 360));
          frameQueue.put(frame);
          sequenceNr++;
          if (frameQueue.size() > 20) Utils.sleep(frameQueue.size());
        } catch (Exception e) {
          logger.warn("Exception while reading image : "+e.getMessage());
        }

        Utils.sleep(sleep);
      }
    }

    public void stop() {
      this.running = false;
    }

  }
}
