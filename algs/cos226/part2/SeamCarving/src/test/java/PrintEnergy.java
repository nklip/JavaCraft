/*************************************************************************
 *  Compilation:  javac PrintEnergy.java
 *  Execution:    java PrintEnergy input.png
 *  Dependencies: SeamCarver.java Picture.java StdDraw.java
 * <p>
 *
 *  Read image from file specified as command line argument. Print energy
 *  of each pixel as calculated by SeamCarver object. 
 * 
 *************************************************************************/
@SuppressWarnings({"RedundantSuppression", "ExplicitToImplicitClassMigration"})
public class PrintEnergy {

    static void main(String[] args) {
        String filename = "6x5.png";
        if (args != null && args.length > 0) {
            filename = args[0];
        }
        Picture inputImg = new Picture(ResourceFiles.resolve(SeamCarver.class, filename).toFile());
        System.out.printf("image is %d pixels wide by %d pixels high.\n", inputImg.width(), inputImg.height());
        
        SeamCarver sc = new SeamCarver(inputImg);
        
        System.out.print("Printing energy calculated for each pixel.\n");

        for (int j = 0; j < sc.height(); j++) {
            for (int i = 0; i < sc.width(); i++) {
                System.out.printf("%9.0f ", sc.energy(i, j));
            }

            System.out.println();
        }
        System.out.println(" ");
        //sc.removeVerticalSeam(sc.findVerticalSeam());
        sc.removeHorizontalSeam(sc.findHorizontalSeam());
        sc.removeHorizontalSeam(sc.findHorizontalSeam());
        sc.removeHorizontalSeam(sc.findHorizontalSeam());
        sc.removeHorizontalSeam(sc.findHorizontalSeam());
        System.out.println(" ");
    }

}
