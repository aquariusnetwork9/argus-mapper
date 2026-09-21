package tools.argus.uploader.core.automap;

/** Something that flies the player, one command per tick, until it finishes or is aborted. */
public interface FlightPlan {

    /** The command for this tick, or null once the plan has finished or been aborted. */
    AutoMapController.Command tick(AutoMapController.Frame frame);

    void abort(String reason);

    AutoMapController.State state();

    String abortReason();

    String statusLine();

    String resultLine();
}
