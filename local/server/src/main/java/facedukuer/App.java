package facedukuer;

import org.bytedeco.javacpp.opencv_core;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.converter.BufferedImageHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.opencv.core.Core;
import org.opencv.highgui.VideoCapture;
import org.opencv.highgui.Highgui;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfDMatch;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.core.MatOfRect;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.features2d.DMatch;
import org.opencv.features2d.DescriptorExtractor;
import org.opencv.features2d.DescriptorMatcher;
import org.opencv.features2d.FeatureDetector;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;

import javax.annotation.PostConstruct;
import javax.imageio.ImageIO;
import javax.servlet.http.Part;
import javax.media.jai.JAI;
import javax.media.jai.RenderedOp;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.function.BiConsumer;


import com.sun.media.jai.codec.MemoryCacheSeekableStream;
import com.sun.media.jai.codec.SeekableStream;

@SpringBootApplication
@RestController
public class App {
    Logger log = LoggerFactory.getLogger(FaceDetector.class);

    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
	System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
    }

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

    @Autowired
    FaceDetector faceDetector;

    @Autowired
    FaceExtractor faceExtractor;

    @Bean
    BufferedImageHttpMessageConverter bufferedImageHttpMessageConverter() {
        return new BufferedImageHttpMessageConverter();
    }

    @RequestMapping(value = "/")
    String hello() {
        return "Hello World!";
    }

    @RequestMapping(value = "/duker", method = RequestMethod.POST)
    String duker(@RequestParam Part file) throws IOException {
		byte[] temp = readStream(file.getInputStream());
		Mat source = Highgui.imdecode(new MatOfByte(temp), Highgui.IMREAD_GRAYSCALE);
		source = faceDetector.detectFaces(source);
		boolean result = faceExtractor.judge("misaki", source);

		if (result)
		  return "true!";
		else
		  return "false";
    }

    private static byte[] readStream(InputStream stream) throws IOException {
        // Copy content of the image to byte-array
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[16384];

        while ((nRead = stream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }

        buffer.flush();
        byte[] temporaryImageInMemory = buffer.toByteArray();
        buffer.close();
        stream.close();
        return temporaryImageInMemory;
    }

}

@Component
class FaceDetector {
    File classifierFile;

    CascadeClassifier classifier;

    Logger log = LoggerFactory.getLogger(FaceDetector.class);

    // 顔検出
    public Mat detectFaces(Mat source) {
      File settingFile = new File("/home/misaki/opencv-2.4.13/data/haarcascades/haarcascade_frontalface_default.xml");
      if (!settingFile.exists()) {
        throw new RuntimeException("Can't read setting file.");
      }

      MatOfRect faceDetections = new MatOfRect();
      CascadeClassifier faceDetector = new CascadeClassifier(settingFile.getAbsolutePath());      

      faceDetector.detectMultiScale(source, faceDetections);

      log.info(String.format("Detected %s faces.", faceDetections.toArray().length));

      Mat face = null;
      if (faceDetections.toArray().length > 0) {
        Rect rect = faceDetections.toList().get(0);
        face = new Mat(source, rect);
      }

      return face;
    }
}

@Component
class FaceExtractor {
  /**
   * 距離の最小値を求める
   * @param name 人物名
   * @param face カメラで入力された画像
   * @return 距離の最小値
   */

  Logger log = LoggerFactory.getLogger(FaceDetector.class);

  public float calcDistance(String name, Mat face) {
    float min_dist = 100000;
    try {
      FeatureDetector detector = FeatureDetector.create(FeatureDetector.DENSE);

      MatOfKeyPoint keyPoint1 = new MatOfKeyPoint();
      detector.detect(face, keyPoint1);

      DescriptorExtractor extractor = DescriptorExtractor.create(DescriptorExtractor.BRISK);
      Mat descriptors1 = new Mat();
      extractor.compute(face, keyPoint1, descriptors1);

      DescriptorMatcher matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING);

      for (int i = 2; i <= 12; i++) {
	String fileName = String.format("./%s/%s_%02d.jpg", name, name, i);

	Mat img = Highgui.imread(fileName);
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

    return min_dist;
  }

  public boolean judge(String name, Mat faces) {
    float distance = calcDistance(name, faces);
    log.info("min_dist: " + distance);

    if (distance < 100)
		return true;
    else
		return false;
  }
}
