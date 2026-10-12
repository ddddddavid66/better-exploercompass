package com.chaosthedude.explorerscompass.unified.client;

/** 所有浮层统一接管输入，避免事件穿透到结构列表。 */
public interface PopupLayer {
    boolean isOpen();
    void close();
    boolean mouseClicked(double x, double y, int button);
    boolean mouseScrolled(double delta);
    boolean mouseDragged(double y);
    boolean mouseReleased();
    boolean keyPressed(int key, int scanCode, int modifiers);
    boolean charTyped(char character, int modifiers);
}
