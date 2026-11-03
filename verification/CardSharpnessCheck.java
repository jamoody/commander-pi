import forge.neo.card.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.scene.image.*;
import javafx.stage.Stage;
import java.lang.reflect.*;
import java.util.concurrent.*;
public class CardSharpnessCheck {
 static CardNode node; static ImageView art; static Image full; static Method sharpen;
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);System.out.println("PASS: "+s);}
 static void fx(Runnable r)throws Exception{FutureTask<Void> t=new FutureTask<>(r,null);Platform.runLater(t);t.get(10,TimeUnit.SECONDS);}
 static void field(String name,Object value)throws Exception{Field f=CardNode.class.getDeclaredField(name);f.setAccessible(true);f.set(node,value);}
 static void sharp(){try{sharpen.invoke(node);}catch(Exception e){throw new RuntimeException(e);}}
 public static void main(String[] args)throws Exception{
 System.setProperty("neo.hoverZoom","150"); System.setProperty("neo.images.hq","true");
 Platform.startup(()->{});
 try {
 fx(()->{try{
 node=new CardNode(100);Stage stage=new Stage();stage.setScene(new Scene(new Pane(node),600,500));stage.show();
 Field f=CardNode.class.getDeclaredField("art");f.setAccessible(true);art=(ImageView)f.get(node);
 full=new WritableImage(488,680);field("fullArt",full);field("shownImageKey","sharpness-fixture");art.setImage(full);
 sharpen=CardNode.class.getDeclaredMethod("sharpenArt");sharpen.setAccessible(true);sharp();
 }catch(Exception e){throw new RuntimeException(e);}});
 for(int i=0;i<30;i++){Thread.sleep(100);fx(()->sharp());if(art.getImage()!=full)break;}
 fx(()->{check(art.getImage()!=full,"small cached card prepared");node.setCardWidth(280);check(art.getImage()==full || art.getImage().getWidth()>=280,"enlargement immediately replaces undersized thumbnail");});
 fx(()->{node.setCardWidth(100);sharp();try{Method m=CardNode.class.getDeclaredMethod("hoverIn");m.setAccessible(true);m.invoke(node);}catch(Exception e){throw new RuntimeException(e);}check(art.getImage()==full || art.getImage().getWidth()>=150,"hover uses enough image pixels for enlarged card");});
 System.out.println("CARD SHARPNESS CHECK PASSED");
 }finally{Platform.exit();}
 }
}

