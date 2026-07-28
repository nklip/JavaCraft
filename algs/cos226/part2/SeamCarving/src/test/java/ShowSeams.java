/*************************************************************************
 *  Compilation:  javac ShowSeams.java
 *  Execution:    java ShowSeams input.png
 *  Dependencies: SeamCarver.java SCUtility.java Picture.java StdDraw.java
 * <p>
 *
 *  Read image from file specified as command line argument. Show 3 images 
 *  original image as well as horizontal and vertical seams of that image.
 *  Each image hides the previous one - drag them to see all three.
 *
 *************************************************************************/
@SuppressWarnings({"RedundantSuppression", "ExplicitToImplicitClassMigration"})
public class ShowSeams {

    private static void showHorizontalSeam(SeamCarver sc) {
        Picture ep = SCUtility.toEnergyPicture(sc);
        int[] horizontalSeam = sc.findHorizontalSeam();
        Picture epOverlay = SCUtility.seamOverlay(ep, true, horizontalSeam);
        epOverlay.show();
    }

    private static void showVerticalSeam(SeamCarver sc) {
        Picture ep = SCUtility.toEnergyPicture(sc);
        int[] verticalSeam = sc.findVerticalSeam();
        Picture epOverlay = SCUtility.seamOverlay(ep, false, verticalSeam);
        epOverlay.show();
    }

    static void main(String[] args) {
        Picture inputImg = new Picture(ResourceFiles.resolve(SeamCarver.class, args[0]).toFile());
        System.out.printf("image is %d columns by %d rows\n", inputImg.width(), inputImg.height());
        inputImg.show();        
        SeamCarver sc = new SeamCarver(inputImg);
        
        System.out.print("Displaying horizontal seam calculated.\n");
        showHorizontalSeam(sc);

        System.out.print("Displaying vertical seam calculated.\n");
        showVerticalSeam(sc);
    }

}
