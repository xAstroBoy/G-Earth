package gearth.protocol;

import gearth.misc.StringifyAble;

public class HMessage implements StringifyAble {

    public enum Direction {
        TOSERVER,
        TOCLIENT
    }

    private HPacket hPacket;
    private Direction direction;
    private int index;

    private boolean isBlocked;

    // The extension that injected/spoofed this packet, if any — set only for packets an extension sent
    // (so the logger can show WHICH one). Purely in-memory: never serialized over the extension protocol.
    private String injectedBy = null;

    public HMessage(HPacketFormat format, String fromString) {
        // A little bit hacky to get the correct packet class inside constructFromString.
        this.hPacket = format.createPacket(0);
        constructFromString(fromString);
    }

    public HMessage(HMessage message) {
        constructFromHMessage(message);
    }

    public HMessage(HPacket packet, Direction direction, int index) {
        this.direction = direction;
        this.hPacket = packet;
        this.index = index;
        isBlocked = false;
    }

    public int getIndex() {
        return index;
    }

    public void setBlocked(boolean block) {
        isBlocked = block;
    }
    public boolean isBlocked() {
        return isBlocked;
    }

    public HPacket getPacket() {
        return hPacket;
    }
    public Direction getDestination() {
        return direction;
    }

    /** The extension that injected this packet, or null for ordinary captured traffic. */
    public String getInjectedBy() {
        return injectedBy;
    }
    public void setInjectedBy(String injectedBy) {
        this.injectedBy = injectedBy;
    }
    public boolean isInjected() {
        return injectedBy != null;
    }

    public boolean isCorrupted() {
        return hPacket.isCorrupted();
    }


    @Override
    public String stringify() {
        String s = (isBlocked ? "1" : "0") + "\t" + index + "\t" + direction.name() + "\t" + hPacket.stringify();
        return s;
    }

    @Override
    public void constructFromString(String str) {
        String[] parts = str.split("\t", 4);
        this.isBlocked = parts[0].equals("1");
        this.index = Integer.parseInt(parts[1]);
        this.direction = parts[2].equals("TOCLIENT") ? Direction.TOCLIENT : Direction.TOSERVER;
        this.hPacket = hPacket.getFormat().createPacket(0);
        this.hPacket.constructFromString(parts[3]);
    }

    public void constructFromHMessage(HMessage message) {
        this.isBlocked = message.isBlocked();
        this.index = message.getIndex();
        this.direction = message.getDestination();
        this.hPacket = message.getPacket().copy();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof HMessage)) return false;

        HMessage message = (HMessage) obj;

        return message.hPacket.equals(hPacket) && (direction == message.direction) && (index == message.index);
    }

//    public static void gearth(String[] args) {
//        HPacket packet3 = new HPacket(81, new byte[]{0,0,0,1,0,0});
//
//        HPacket packet = new HPacket(82, new byte[]{0,0,0,1,0,0});
//        HMessage message = new HMessage(packet, Side.TOSERVER, 5);
//
//        String stringed = message.stringify();
//
//        HMessage message2 = new HMessage(stringed);
//        HPacket packet1 = message2.getPacket();
//
//        System.out.println(message.equals(message2));
//        System.out.println(packet.equals(packet1));
//        System.out.println(packet.equals(packet3));
//    }
}
