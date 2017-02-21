import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Random;

import javax.swing.*;

import org.opencv.core.Core;
import org.opencv.highgui.VideoCapture;
import org.opencv.highgui.Highgui;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

public class Panel extends JPanel{
  private static final long serialVersionUID = 1L;
  private BufferedImage image;

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

  public static void connect(File file) {
    String boundary = generateBoundary();
    String attachmentName = "file";
    String attachmentFileName = "webcam.jpg";
    String crlf = "\r\n";
    String twoHyphens = "--";

    try {
       // 接続
       URL url = new URL("http://192.168.33.130:8080/duker");
       HttpURLConnection conn = (HttpURLConnection) url.openConnection();
       conn.setRequestMethod("POST");
       conn.setDoOutput(true);
       conn.setDoInput(true);
       conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

       DataOutputStream out = new DataOutputStream(conn.getOutputStream());
       out.writeBytes(twoHyphens + boundary + crlf);
       out.writeBytes("Content-Disposition: form-data; name=\"" +
           attachmentName + "\";filename=\"" +
           attachmentFileName + "\"" + crlf);
       out.writeBytes(crlf);

       out.writeBytes(twoHyphens + boundary + crlf);
       out.write(file.getName().getBytes("UTF-8"));
       out.writeBytes("\"\r\n");
       BufferedInputStream in = new BufferedInputStream(new FileInputStream(file));
       int buff = 0;
       while((buff = in.read()) != -1){
           out.write(buff);
       }
       out.writeBytes(crlf);
       in.close();

       out.writeBytes(twoHyphens + boundary + twoHyphens);
       out.flush();
       out.close();

       InputStream stream = conn.getInputStream();
       BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
       String responseData = null;
       while((responseData = reader.readLine()) != null){
           System.out.print(responseData);
       }
       stream.close();
    } catch (Exception e) {
       e.printStackTrace();
    }
  }

  private static String generateBoundary(){
    String chars = "1234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ-_";
    Random rand = new Random();
    String boundary = "";
    for(int i = 0; i < 40; i++){
        int r = rand.nextInt(chars.length());
        boundary += chars.substring(r, r + 1);
    }
    return boundary;
  }

  public static void main(String arg[]){
    String userDir = System.getProperty("user.dir").replaceAll("\\\\", "/");

    //System.out.println(System.getProperty("java.library.path"));

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

          new Thread(new Runnable() {
            @Override
            public void run() {
              File img = new File("./resources/webcam.jpg");
              connect(img);
            }
          }).start();

          frame.setSize(webcam_image.width()+40,webcam_image.height()+60);
          temp=matToBufferedImage(webcam_image);
          panel.setimage(temp);

          panel.repaint();
        } else {
          System.out.println(" --(!) No captured frame -- ");
        }
      }
    }
    return;
  }
}


