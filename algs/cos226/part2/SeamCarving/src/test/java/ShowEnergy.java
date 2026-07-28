/*************************************************************************
 *  Compilation:  javac ShowEnergy.java
 *  Execution:    java ShowEnergy input.png
 *  Dependencies: SeamCarver.java SCUtility.java Picture.java StdDraw.java
 * <p>
 *
 *  Read image from file specified as command line argument. Show original
 *  image (only useful if image is large enough).
 *
 *************************************************************************/

@SuppressWarnings({"RedundantSuppression", "ExplicitToImplicitClassMigration"})
public class ShowEnergy {

    static void main(String[] args) {
        String filename = "HJocean.png";
        if (args != null && args.length > 0) {
            filename = args[0];
        }
        Picture inputImg = new Picture(ResourceFiles.resolve(SeamCarver.class, filename).toFile());
        System.out.printf("image is %d columns by %d rows\n", inputImg.width(), inputImg.height());
        inputImg.show();        
        SeamCarver sc = new SeamCarver(inputImg);
        
        System.out.print("Displaying energy calculated for each pixel.\n");
        SCUtility.showEnergy(sc);
    }

}
