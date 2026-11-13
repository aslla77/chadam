// 점검용: 에뮬 screencap PNG에서 차 QR 글자를 읽는다(폰 카메라로 Mac 화면을 찍을 수 없을 때 딥링크 경로용). ZXing core만 쓴다.
// 사용: JAR=$(find ~/.gradle/caches/modules-2/files-2.1/com.google.zxing/core/3.5.4 -name 'core-3.5.4.jar'); java -cp "$JAR" QrPng.java car.png
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

public class QrPng {
  public static void main(String[] a) throws Exception {
    BufferedImage img = ImageIO.read(new File(a[0]));
    int w = img.getWidth(), h = img.getHeight();
    byte[] y = new byte[w * h];
    for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) {
      int p = img.getRGB(i, j);
      y[j * w + i] = (byte) ((((p >> 16) & 255) * 299 + ((p >> 8) & 255) * 587 + (p & 255) * 114) / 1000);
    }
    LuminanceSource src = new PlanarYUVLuminanceSource(y, w, h, 0, 0, w, h, false);
    System.out.println(new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(src))).getText());
  }
}
