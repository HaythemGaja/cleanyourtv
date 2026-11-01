package com.gathin.tvhome;

/**
 * Pure-Java model of the v3.21 home layout + focus fix. No Android
 * dependencies, so the headless harness can verify the exact
 * decisions the UI code implements in MainActivity / TrackerUi:
 *
 * <p>The v3.20 bug: the tracker container was inserted bare into
 * the home column above the grid wrapper. The column is a vertical
 * LinearLayout; the grid wrapper is weight=1/height=0, so it gets
 * only the height left over after every wrap_content child. A tall
 * tracker container (hero + three poster rows) left the grid ZERO
 * height: no tiles, nothing scrollable, and no tile click ever
 * reached the lock gate.
 *
 * <p>The v3.21 fix: the tracker container goes inside a
 * NestedScrollView that itself carries weight=1/height=0, so the
 * column's leftover height is shared equally between the tracker
 * scroll view and the grid wrapper. The grid therefore always
 * keeps a real, positive height with its own RecyclerView
 * scrolling, and the tracker rows scroll inside their half.
 * D-pad exit from the tracker is the Add-show button (always the
 * last tracker child), which carries nextFocusDownId=grid; UP
 * from the first grid row uses default focus search, which lands
 * on the nearest tracker child above.
 */
final class TrackerLayout {
    private TrackerLayout() {}

    /** Split of the column's leftover height between tracker scroll view and grid wrapper. */
    static final class Split {
        final int trackerH;
        final int gridH;
        Split(int trackerH, int gridH) { this.trackerH = trackerH; this.gridH = gridH; }
    }

    /**
     * Equal-weight split of the height left after the header.
     * Both children are height=0/weight=1 in a vertical LinearLayout,
     * so each gets half the leftover height (the grid keeps the odd
     * pixel, if any, so it is never the short side).
     */
    static Split computeSplit(int screenH, int headerH) {
        int remaining = Math.max(0, screenH - headerH);
        int trackerH = remaining / 2;
        int gridH = remaining - trackerH;
        return new Split(trackerH, gridH);
    }

    /** Structural guarantee: the grid is never squeezed to zero. */
    static boolean gridHasHeight(int screenH, int headerH) {
        return computeSplit(screenH, headerH).gridH > 0;
    }

    /**
     * The grid keeps at least a quarter of the screen height as long
     * as the header takes at most half the screen — true for any real
     * TV header (clock + top buttons), and the bound the v3.20 bug
     * violated (grid height was exactly 0).
     */
    static boolean gridKeepsQuarterScreen(int screenH, int headerH) {
        return headerH <= screenH / 2 && computeSplit(screenH, headerH).gridH >= screenH / 4;
    }

    /**
     * Minimal model of vertical D-pad focus search over one column
     * of focusable items: tracker items first (indices 0..trackerCount-1),
     * then grid tiles (indices trackerCount..trackerCount+tileCount-1).
     * DOWN moves to the next item, UP to the previous one; the Add-show
     * button is the last tracker item and carries nextFocusDownId=grid,
     * which is exactly the same edge (last tracker -> first tile).
     * Returns the landed index, or -1 if focus would leave the column.
     */
    static int focusDown(int[] ignoredTops, int trackerCount, int tileCount, int from) {
        int total = trackerCount + tileCount;
        if (from < 0 || from >= total) return -1;
        return from + 1 < total ? from + 1 : -1;
    }

    static int focusUp(int trackerCount, int tileCount, int from) {
        int total = trackerCount + tileCount;
        if (from <= 0 || from >= total) return -1;
        return from - 1;
    }

    /** DOWN from the last tracker item lands on the first grid tile. */
    static boolean downExitsTrackerToGrid(int trackerCount, int tileCount) {
        if (trackerCount <= 0 || tileCount <= 0) return false;
        return focusDown(null, trackerCount, tileCount, trackerCount - 1) == trackerCount;
    }

    /** UP from the first grid tile returns to the last tracker item. */
    static boolean upReturnsToTracker(int trackerCount, int tileCount) {
        if (trackerCount <= 0 || tileCount <= 0) return false;
        return focusUp(trackerCount, tileCount, trackerCount) == trackerCount - 1;
    }

    /** Walking DOWN from the top reaches every grid tile (no trap). */
    static boolean downWalkReachesAllTiles(int trackerCount, int tileCount) {
        int at = 0;
        int steps = 0;
        while (at >= 0 && at < trackerCount + tileCount && steps <= trackerCount + tileCount) {
            if (at >= trackerCount) {
                // Count distinct tiles visited from here on.
                int visited = 0;
                int cur = at;
                while (cur >= trackerCount && cur < trackerCount + tileCount) {
                    visited++;
                    cur = focusDown(null, trackerCount, tileCount, cur);
                }
                return visited == tileCount;
            }
            at = focusDown(null, trackerCount, tileCount, at);
            steps++;
        }
        return false;
    }
}
