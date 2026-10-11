package com.rstech.simupack.client.ui.preview;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Headless tests: no Minecraft, RenderSystem, window, or OpenGL context is needed. */
class ScenePickMathTest {
    private static final float EPSILON = 0.002F;
    private static final int[] VIEWPORT = {137, 91, 900, 600};

    @Test
    void zoomDoesNotShrinkDepthAndSliceStructureAtAnyRotation() {
        Vector3f minimum = new Vector3f(0, 0, 0);
        Vector3f maximum = new Vector3f(5, 3, 5);
        boolean reproducedOldClipping = false;
        for (Vector3f target : new Vector3f[]{new Vector3f(2.5F, 1.5F, 2.5F),
                new Vector3f(-7F, 6F, 10F)}) {
            float depth = ScenePickMath.orthographicDepthHalfExtent(target, minimum, maximum, 0.1F);
            for (int yaw = -180; yaw < 180; yaw += 45) {
                for (int pitch : new int[]{-75, -25, 25, 75}) {
                    double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
                    Vector3f eye = new Vector3f((float) Math.cos(y), (float) Math.tan(p),
                            (float) Math.sin(y)).normalize().mul(0.1F).add(target);
                    Matrix4f view = lookAt(eye, target);
                    for (float zoom : new float[]{0.1F, 0.25F, 1F, 4.172F, 20F}) {
                        Matrix4f fixed = new Matrix4f().ortho(-zoom, zoom, -zoom, zoom, -depth, depth).mul(view);
                        Matrix4f old = new Matrix4f().ortho(-zoom, zoom, -zoom, zoom, -zoom, zoom).mul(view);
                        for (float x : new float[]{0, 5}) {
                            for (float yy : new float[]{0, 3}) {
                                for (float z : new float[]{0, 5}) {
                                    Vector4f corner = new Vector4f(x, yy, z, 1F);
                                    Vector4f clipped = old.transform(new Vector4f(corner));
                                    Vector4f visible = fixed.transform(new Vector4f(corner));
                                    reproducedOldClipping |= Math.abs(clipped.z / clipped.w) > 1F;
                                    assertTrue(Math.abs(visible.z / visible.w) < 1F,
                                            "Whole structure must remain between the depth planes after zoom/rotation/pan");
                                    assertEquals(clipped.x / clipped.w, visible.x / visible.w, EPSILON);
                                    assertEquals(clipped.y / clipped.w, visible.y / visible.w, EPSILON);
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue(reproducedOldClipping, "Regression must reproduce the previous zoom-linked Z clipping");
    }

    @Test
    void pointAboveLookAtProjectsUpwardAndIsPickedAboveNotBelow() {
        Matrix4f view = lookAt(new Vector3f(0, 0, 10), new Vector3f());
        Matrix4f projection = new Matrix4f().ortho(-4, 4, -3, 3, 1, 30);
        Vector3f above = new Vector3f(0, 1.25F, 0);
        Vector2f centerPixel = project(projection, view, VIEWPORT, new Vector3f());
        Vector2f abovePixel = project(projection, view, VIEWPORT, above);

        // OpenGL Y increases upward; GUI Y increases downward. These known pixel
        // values independently catch the old forward.cross(right) mirrored-up basis.
        assertEquals(587F, centerPixel.x, EPSILON);
        assertEquals(391F, centerPixel.y, EPSILON);
        assertEquals(516F, abovePixel.y, EPSILON);
        assertTrue(abovePixel.y > centerPixel.y);
        double centerGuiY = (1500D - centerPixel.y) * 600D / 1500D;
        double aboveGuiY = (1500D - abovePixel.y) * 600D / 1500D;
        assertTrue(aboveGuiY < centerGuiY, "Above the lookAt target must move upward on the GUI");

        ScenePickMath.Ray ray = rayThroughGui(projection, view, VIEWPORT, above,
                1000, 600, 2500, 1500);
        assertVector(new Vector3f(0, 1.25F, 9), ray.near());
        assertVector(new Vector3f(0, 1.25F, -20), ray.far());
        assertPassesThrough(ray, above);
        assertTrue(distanceToLine(ray, new Vector3f(0, -1.25F, 0)) > 2,
                "The vertically mirrored target must not lie on the pick ray");
    }

    @Test
    void rawGuiMouseUsesWholeSurfaceRatiosBeforeOffOriginViewportMapping() {
        // Deliberately use unequal X/Y ratios and a viewport smaller than the FBO.
        Vector2f mouse = ScenePickMath.framebufferMouse(123.25, 87.75, 800, 500, 2000, 1750);
        assertNotNull(mouse);
        assertEquals(308.125F, mouse.x, 0.0001F);
        assertEquals(1442.875F, mouse.y, 0.0001F);

        int[] viewport = {211, 173, 1300, 1100};
        Matrix4f view = lookAt(new Vector3f(4, 3, 8), new Vector3f(0.2F, -0.1F, 0));
        Matrix4f projection = new Matrix4f().ortho(-5, 5, -4, 4, -3, 40);
        Vector3f point = new Vector3f(0.7F, 0.6F, -0.4F);
        ScenePickMath.Ray ray = rayThroughGui(projection, view, viewport, point,
                800, 500, 2000, 1750);
        assertPassesThrough(ray, point);
        assertCameraDepth(view, ray, -3, 40);
    }

    @Test
    void orthographicNegativeNearPlaneKeepsGeometryBehindAnEyeInsideBounds() {
        Matrix4f view = lookAt(new Vector3f(), new Vector3f(0, 0, -1));
        Matrix4f projection = new Matrix4f().ortho(-2, 2, -2, 2, -4, 6);
        ScenePickMath.Ray ray = rayThroughGui(projection, view, VIEWPORT,
                new Vector3f(0, 0, 0.5F), 1000, 600, 2500, 1500);

        assertVector(new Vector3f(0, 0, 4), ray.near());
        assertVector(new Vector3f(0, 0, -6), ray.far());
        assertCameraDepth(view, ray, -4, 6);
        assertPassesThrough(ray, new Vector3f(0, 0, 0.5F));
        // The eye is inside [-1,1]^3, but the visible segment starts at z=4,
        // not at the eye. A ray started at the eye would miss this entry face.
        assertEquals(0.3, slabEntry(ray, new Vector3f(-1), new Vector3f(1)), 0.00001);
        assertEquals(0.1, slabEntry(ray, new Vector3f(-1, -1, 2),
                new Vector3f(1, 1, 3)), 0.00001);
    }

    @Test
    void perspectiveRayUsesHomogeneousDivideAndCorrectNearFarDepths() {
        Matrix4f view = lookAt(new Vector3f(0, 0, 10), new Vector3f());
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(60), 1.5F, 1, 31);
        Vector3f point = new Vector3f(1.5F, 0.75F, 0);
        ScenePickMath.Ray ray = rayThroughGui(projection, view, VIEWPORT, point,
                1000, 600, 2500, 1500);

        // Along this line from the eye, x/y scale by depth / 10. These
        // analytical endpoints do not depend on the inverse-projection oracle.
        assertVector(new Vector3f(0.15F, 0.075F, 9), ray.near());
        assertVector(new Vector3f(4.65F, 2.325F, -21), ray.far());
        assertCameraDepth(view, ray, 1, 31);
        assertPassesThrough(ray, point);
    }

    @Test
    void rotatedZoomedAndResizedOrthographicViewsStillPickTheRenderedPoint() {
        Vector3f target = new Vector3f(1, -0.5F, 2);
        Vector3f point = new Vector3f(1.3F, 0.1F, 1.7F);
        Vector3f[] eyes = {new Vector3f(7, 4, 11), new Vector3f(-8, 6, 5),
                new Vector3f(4, -7, -9)};
        for (Vector3f eye : eyes) {
            Matrix4f view = lookAt(eye, target);
            for (float halfHeight : new float[]{1.5F, 4, 9}) {
                for (int width : new int[]{500, 1200}) {
                    int[] viewport = {73, 129, width, 700};
                    float halfWidth = halfHeight * width / 700F;
                    Matrix4f projection = new Matrix4f().ortho(-halfWidth, halfWidth,
                            -halfHeight, halfHeight, -7, 60);
                    ScenePickMath.Ray ray = rayThroughGui(projection, view, viewport, point,
                            960, 540, 1920, 1080);
                    assertPassesThrough(ray, point);
                    assertCameraDepth(view, ray, -7, 60);
                    Vector3f direction = new Vector3f(ray.far()).sub(ray.near()).normalize();
                    assertVector(new Vector3f(target).sub(eye).normalize(), direction);
                }
            }
        }
    }

    @Test
    void perspectiveRotationFieldOfViewAndAspectUseCurrentMatrices() {
        Vector3f target = new Vector3f(-1, 0.5F, 2);
        Vector3f point = new Vector3f(-0.6F, 0.8F, 1.8F);
        for (Vector3f eye : new Vector3f[]{new Vector3f(6, 4, 12), new Vector3f(-9, 5, -7)}) {
            Matrix4f view = lookAt(eye, target);
            for (float fov : new float[]{35, 85}) {
                for (int width : new int[]{500, 1300}) {
                    int[] viewport = {83, 117, width, 700};
                    Matrix4f projection = new Matrix4f().perspective(
                            (float) Math.toRadians(fov), width / 700F, 0.5F, 60);
                    ScenePickMath.Ray ray = rayThroughGui(projection, view, viewport, point,
                            960, 540, 1920, 1080);
                    assertPassesThrough(ray, point);
                    assertCameraDepth(view, ray, 0.5F, 60);
                    assertTrue(distanceToLine(ray, eye) < EPSILON,
                            "A perspective ray's line must also pass through the real camera eye");
                }
            }
        }
    }

    @Test
    void nearestOverlappingBoxWinsAndSpaceBetweenBoxesRemainsAMiss() {
        Matrix4f view = lookAt(new Vector3f(0, 0, 10), new Vector3f());
        Matrix4f projection = new Matrix4f().ortho(-4, 4, -3, 3, 1, 30);
        ScenePickMath.Ray ray = rayThroughGui(projection, view, VIEWPORT, new Vector3f(),
                1000, 600, 2500, 1500);
        double front = slabEntry(ray, new Vector3f(-1, -1, 3), new Vector3f(1, 1, 4));
        double back = slabEntry(ray, new Vector3f(-1, -1, -1), new Vector3f(1, 1, 0));
        assertEquals(5D / 29D, front, 0.00001);
        assertEquals(9D / 29D, back, 0.00001);
        assertTrue(front < back, "The front face, not block center or iteration order, sets depth");
        assertEquals(Double.POSITIVE_INFINITY, slabEntry(ray,
                new Vector3f(-2, -1, -1), new Vector3f(-0.25F, 1, 4)));
        assertEquals(Double.POSITIVE_INFINITY, slabEntry(ray,
                new Vector3f(0.25F, -1, -1), new Vector3f(2, 1, 4)));
    }

    @Test
    void subpixelEdgesAndCornersDoNotSnapToABoxCenterOrIntegerPixel() {
        int[] viewport = {103, 67, 1000, 1000};
        Matrix4f view = lookAt(new Vector3f(0, 0, 10), new Vector3f());
        Matrix4f projection = new Matrix4f().ortho(-2, 2, -2, 2, 1, 30);
        Vector3f minimum = new Vector3f(-1, -1, -0.5F);
        Vector3f maximum = new Vector3f(1, 1, 0.5F);
        // 0.0004 world units = 0.1 framebuffer pixels here. Test all four
        // corners as well as the midpoint of a vertical edge.
        for (float signX : new float[]{-1, 1}) {
            for (float signY : new float[]{-1, 1}) {
                Vector3f inside = new Vector3f(signX * 0.9996F, signY * 0.9996F, 0);
                Vector3f outsideX = new Vector3f(signX * 1.0004F, signY * 0.9996F, 0);
                Vector3f outsideY = new Vector3f(signX * 0.9996F, signY * 1.0004F, 0);
                assertHit(projection, view, viewport, inside, minimum, maximum, true);
                assertHit(projection, view, viewport, outsideX, minimum, maximum, false);
                assertHit(projection, view, viewport, outsideY, minimum, maximum, false);
                float pixelDifference = Math.abs(project(projection, view, viewport, outsideX).x
                        - project(projection, view, viewport, inside).x);
                assertEquals(0.2F, pixelDifference, 0.0002F);
            }
        }
        assertHit(projection, view, viewport, new Vector3f(0.9996F, 0, 0), minimum, maximum, true);
        assertHit(projection, view, viewport, new Vector3f(1.0004F, 0, 0), minimum, maximum, false);
    }

    @Test
    void viewportIncludesLowerEdgesButRejectsUpperEdgesAndOutsideCoordinates() {
        Matrix4f inverse = new Matrix4f();
        int[] viewport = {31, 47, 300, 200};
        assertNotNull(ScenePickMath.ray(inverse, viewport, 31, 47));
        assertNotNull(ScenePickMath.ray(inverse, viewport, Math.nextDown(331F), Math.nextDown(247F)));
        assertNull(ScenePickMath.ray(inverse, viewport, Math.nextDown(31F), 100));
        assertNull(ScenePickMath.ray(inverse, viewport, 100, Math.nextDown(47F)));
        assertNull(ScenePickMath.ray(inverse, viewport, 331, 100));
        assertNull(ScenePickMath.ray(inverse, viewport, 100, 247));
        assertNull(ScenePickMath.ray(inverse, viewport, 900, 900));
        assertNull(ScenePickMath.ray(inverse, new int[]{0, 0, 0, 200}, 0, 100));
        assertNull(ScenePickMath.ray(inverse, new int[]{0, 0, 200, -1}, 100, 0));
        assertNull(ScenePickMath.ray(inverse, new int[]{0, 0, 200}, 100, 100));
    }

    @Test
    void nonfiniteMouseAndInvalidSurfaceSizesAreRejected() {
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertNull(ScenePickMath.framebufferMouse(value, 10, 800, 600, 1600, 1200));
            assertNull(ScenePickMath.framebufferMouse(10, value, 800, 600, 1600, 1200));
        }
        for (int value : new int[]{0, -1}) {
            assertNull(ScenePickMath.framebufferMouse(10, 10, value, 600, 1600, 1200));
            assertNull(ScenePickMath.framebufferMouse(10, 10, 800, value, 1600, 1200));
            assertNull(ScenePickMath.framebufferMouse(10, 10, 800, 600, value, 1200));
            assertNull(ScenePickMath.framebufferMouse(10, 10, 800, 600, 1600, value));
        }
        for (float value : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertNull(ScenePickMath.ray(new Matrix4f(), VIEWPORT, value, 200));
            assertNull(ScenePickMath.ray(new Matrix4f(), VIEWPORT, 200, value));
        }
    }

    @Test
    void singularNonfiniteAndCollapsedInverseMatricesProduceNoRay() {
        int[] viewport = {0, 0, 200, 200};
        Matrix4f[] invalid = {
                new Matrix4f().zero().invert(),
                new Matrix4f().zero(),
                new Matrix4f().zero().m33(1), // finite constant world point: zero-length ray
                new Matrix4f().m33(0.000000001F), // unusable homogeneous divisor
                new Matrix4f().m00(Float.NaN),
                new Matrix4f().m30(Float.POSITIVE_INFINITY)
        };
        for (Matrix4f inverse : invalid) {
            assertNull(ScenePickMath.ray(inverse, viewport, 100, 100));
        }
    }

    private static Matrix4f lookAt(Vector3f eye, Vector3f target) {
        return new Matrix4f().lookAt(eye, target, new Vector3f(0, 1, 0));
    }

    /** Independent forward oracle: known world point -> actual P * V -> GL pixel. */
    private static Vector2f project(Matrix4f projection, Matrix4f view, int[] viewport, Vector3f point) {
        Vector4f clip = new Matrix4f(projection).mul(view).transform(new Vector4f(point, 1));
        assertTrue(Float.isFinite(clip.w) && clip.w > 0, "Fixture must be in front of the camera");
        float ndcX = clip.x / clip.w;
        float ndcY = clip.y / clip.w;
        float ndcZ = clip.z / clip.w;
        assertTrue(ndcX > -1 && ndcX < 1 && ndcY > -1 && ndcY < 1
                && ndcZ >= -1 && ndcZ <= 1, "Fixture must lie inside the rendered clip volume");
        return new Vector2f(viewport[0] + (ndcX + 1) * viewport[2] / 2F,
                viewport[1] + (ndcY + 1) * viewport[3] / 2F);
    }

    private static ScenePickMath.Ray rayThroughGui(Matrix4f projection, Matrix4f view, int[] viewport,
                                                   Vector3f point, int guiWidth, int guiHeight,
                                                   int framebufferWidth, int framebufferHeight) {
        Vector2f expectedPixel = project(projection, view, viewport, point);
        double mouseX = (double) expectedPixel.x * guiWidth / framebufferWidth;
        double mouseY = (framebufferHeight - (double) expectedPixel.y) * guiHeight / framebufferHeight;
        Vector2f actualPixel = ScenePickMath.framebufferMouse(mouseX, mouseY, guiWidth, guiHeight,
                framebufferWidth, framebufferHeight);
        assertNotNull(actualPixel);
        assertEquals(expectedPixel.x, actualPixel.x, 0.0002F);
        assertEquals(expectedPixel.y, actualPixel.y, 0.0002F);
        ScenePickMath.Ray ray = ScenePickMath.ray(new Matrix4f(projection).mul(view).invert(),
                viewport, actualPixel.x, actualPixel.y);
        assertNotNull(ray);
        return ray;
    }

    private static void assertVector(Vector3f expected, Vector3f actual) {
        assertEquals(expected.x, actual.x, EPSILON, "world X");
        assertEquals(expected.y, actual.y, EPSILON, "world Y");
        assertEquals(expected.z, actual.z, EPSILON, "world Z");
    }

    private static void assertCameraDepth(Matrix4f view, ScenePickMath.Ray ray, float near, float far) {
        Vector4f nearView = view.transform(new Vector4f(ray.near(), 1));
        Vector4f farView = view.transform(new Vector4f(ray.far(), 1));
        assertEquals(-near, nearView.z, EPSILON, "near endpoint camera-space depth");
        assertEquals(-far, farView.z, EPSILON, "far endpoint camera-space depth");
    }

    private static void assertPassesThrough(ScenePickMath.Ray ray, Vector3f point) {
        Vector3f delta = new Vector3f(ray.far()).sub(ray.near());
        float t = new Vector3f(point).sub(ray.near()).dot(delta) / delta.lengthSquared();
        assertTrue(t >= 0 && t <= 1, "Known visible point must lie between near and far");
        assertTrue(distanceToLine(ray, point) < EPSILON,
                "Pick ray must pass through the independently forward-projected world point");
    }

    private static float distanceToLine(ScenePickMath.Ray ray, Vector3f point) {
        Vector3f direction = new Vector3f(ray.far()).sub(ray.near());
        return new Vector3f(point).sub(ray.near()).cross(direction).length() / direction.length();
    }

    private static void assertHit(Matrix4f projection, Matrix4f view, int[] viewport, Vector3f point,
                                  Vector3f minimum, Vector3f maximum, boolean expectedHit) {
        ScenePickMath.Ray ray = rayThroughGui(projection, view, viewport, point,
                800, 600, 1600, 1200);
        assertPassesThrough(ray, point);
        assertEquals(expectedHit, Double.isFinite(slabEntry(ray, minimum, maximum)),
                "Subpixel hit classification at " + point);
    }

    /** Test-only segment/box oracle; only rays from the production helper are tested. */
    private static double slabEntry(ScenePickMath.Ray ray, Vector3f minimum, Vector3f maximum) {
        double[] start = {ray.near().x, ray.near().y, ray.near().z};
        double[] end = {ray.far().x, ray.far().y, ray.far().z};
        double[] low = {minimum.x, minimum.y, minimum.z};
        double[] high = {maximum.x, maximum.y, maximum.z};
        double entry = 0;
        double exit = 1;
        for (int axis = 0; axis < 3; axis++) {
            double direction = end[axis] - start[axis];
            if (Math.abs(direction) < 1.0E-12) {
                if (start[axis] < low[axis] || start[axis] > high[axis]) {
                    return Double.POSITIVE_INFINITY;
                }
            } else {
                double first = (low[axis] - start[axis]) / direction;
                double second = (high[axis] - start[axis]) / direction;
                entry = Math.max(entry, Math.min(first, second));
                exit = Math.min(exit, Math.max(first, second));
                if (entry > exit) {
                    return Double.POSITIVE_INFINITY;
                }
            }
        }
        return entry;
    }
}
