package me.ichun.mods.ichunutil.common.module.worldportals.common.portal;

import me.ichun.mods.ichunutil.common.core.util.EntityHelper;
import me.ichun.mods.ichunutil.common.entity.EntityBlock;
import me.ichun.mods.ichunutil.common.module.worldportals.common.WorldPortals;
import me.ichun.mods.ichunutil.common.module.worldportals.common.packet.PacketEntityLocation;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityFallingBlock;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.projectile.EntityFireball;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.*;

public abstract class WorldPortal
{
    private EnumFacing faceOn; //The face that shows the world portal.
    private EnumFacing upDir; //Upwards direction of the portal.

    private QuaternionFormula quaternionFormula; //used to calculate the rotation and the positional offset (as well as motion)

    private float scanDistance;

    private AxisAlignedBB plane;
    private AxisAlignedBB flatPlane; //AABB that defines the plane where the magic happens.
    public AxisAlignedBB scanRange;
    public AxisAlignedBB portalInsides;

    private float width;
    private float height;

    private Vec3d position;
    private BlockPos posBlock;

    private HashSet<AxisAlignedBB> collisions;

    private WorldPortal pair;

    public World world;
    public List<Entity> lastScanEntities = new ArrayList<>();
    public HashMap<Entity, Integer> teleportCooldown = new HashMap<>();

    private boolean firstUpdate;

    public WorldPortal(World world)
    {
        this.world = world;
        this.position = new Vec3d(0,0,0);
        this.posBlock = new BlockPos(position);

        this.faceOn = EnumFacing.NORTH;
        this.upDir = EnumFacing.UP;

        this.scanDistance = 3F;

        this.firstUpdate = true;
    }

    public WorldPortal(World world, Vec3d position, EnumFacing faceOn, EnumFacing upDir, float width, float height)
    {
        this.world = world;
        this.position = position;
        this.posBlock = new BlockPos(position);

        this.faceOn = faceOn;
        this.upDir = upDir;

        this.width = width;
        this.height = height;

        this.scanDistance = 3F;

        this.firstUpdate = true;
    }

    public abstract float getPlaneOffset();

    public abstract boolean canCollideWithBorders();

    public abstract String owner(); //mod that owns this;

    @SideOnly(Side.CLIENT)
    public abstract void drawPlane();

    public void setFace(EnumFacing faceOut, EnumFacing upDir)
    {
        this.faceOn = faceOut;
        this.upDir = upDir;
    }

    public EnumFacing getFaceOn()
    {
        return faceOn;
    }

    public EnumFacing getUpDir()
    {
        return upDir;
    }

    public BlockPos getPos()
    {
        return posBlock;
    }

    public float getWidth()
    {
        return width;
    }

    public float getHeight()
    {
        return height;
    }

    public void setSize(float width, float height)
    {
        this.width = width;
        this.height = height;
    }

    public void setScanDistance(float scanDistance)
    {
        this.scanDistance = scanDistance;
        this.plane = null;
        setupAABBs();
    }

    public void updateWorldPortal()
    {
        if(firstUpdate)
        {
            setupAABBs(); //setup AABBs.
            firstUpdate = false;
        }
        Iterator<Map.Entry<Entity, Integer>> ite = teleportCooldown.entrySet().iterator();
        while(ite.hasNext())
        {
            Map.Entry<Entity, Integer> e = ite.next();
            e.setValue(e.getValue() - 1);
            if(e.getValue() < 0)
            {
                ite.remove();
            }
        }
        if(!hasPair())
        {
            return;
        }

        List<Entity> entitiesInRange = world.getEntitiesWithinAABB(Entity.class, scanRange);
        for(int i = entitiesInRange.size() - 1; i >= 0; i--)
        {
            Entity ent = entitiesInRange.get(i);

            WorldPortals.eventHandler.addMonitoredEntity(ent, this);
            if(teleportCooldown.containsKey(ent) || ent instanceof EntityPlayerMP && !ent.worldObj.isRemote)
            {
                continue;
            }

            double[] motions = EntityHelper.simulateMoveEntity(ent, ent.motionX, ent.motionY, ent.motionZ);
            Vec3d newEntPos = new Vec3d(ent.posX + motions[0], ent.posY + ent.getEyeHeight() + motions[1], ent.posZ + motions[2]);
            boolean teleport = false;
            AxisAlignedBB teleportPlane = flatPlane;
            float offset = 0.0F; //should I test player width specifically?
            if(ent instanceof EntityPlayer)
            {
                offset = Math.min(0.325F, (float)Math.abs((flatPlane.minX - ent.posX) * faceOn.getFrontOffsetX() + (flatPlane.minY - ent.posY) * faceOn.getFrontOffsetY() + (flatPlane.minZ - ent.posZ) * faceOn.getFrontOffsetZ()));
                if(!scanRange.offset(faceOn.getFrontOffsetX() * offset, faceOn.getFrontOffsetY() * offset, faceOn.getFrontOffsetZ() * offset).isVecInside(newEntPos) && portalInsides.offset(faceOn.getFrontOffsetX() * offset, faceOn.getFrontOffsetY() * offset, faceOn.getFrontOffsetZ() * offset).isVecInside(newEntPos))
                {
                    teleportPlane = getTeleportPlane(offset);
                    teleport = true;
                }
            }
            else
            {
                if(!scanRange.isVecInside(newEntPos) && portalInsides.isVecInside(newEntPos))
                {
                    teleport = true;
                }
            }

            if(teleport)
            {
                double centerX = (teleportPlane.maxX + teleportPlane.minX) / 2D;
                double centerY = (teleportPlane.maxY + teleportPlane.minY) / 2D;
                double centerZ = (teleportPlane.maxZ + teleportPlane.minZ) / 2D;

                if(pair != null)
                {
                    float[] appliedOffset = getQuaternionFormula().applyPositionalRotation(new float[] { (float)(newEntPos.xCoord - centerX), (float)(newEntPos.yCoord - centerY), (float)(newEntPos.zCoord - centerZ) });
                    float[] appliedMotion = getQuaternionFormula().applyPositionalRotation(new float[] { (float)motions[0], (float)motions[1], (float)motions[2] });
                    float[] appliedRotation = getQuaternionFormula().applyRotationalRotation(new float[] { ent.rotationYaw, ent.rotationPitch, 0F });

                    pair.setupAABBs(); //TODO do I have to double check and make sure the player isn't put back in the offset "portal insides"?
                    AxisAlignedBB pairTeleportPlane = pair.getTeleportPlane(offset);

                    double destX = (pairTeleportPlane.maxX + pairTeleportPlane.minX) / 2D;
                    double destY = (pairTeleportPlane.maxY + pairTeleportPlane.minY) / 2D;
                    double destZ = (pairTeleportPlane.maxZ + pairTeleportPlane.minZ) / 2D;

                    EntityTransformationStack ets = new EntityTransformationStack(ent);
                    ets.translate(destX - ent.posX + appliedOffset[0], destY - (ent.posY + ent.getEyeHeight()) + appliedOffset[1], destZ - ent.posZ + appliedOffset[2]); //go to the centre of the dest portal and offset with the fields
                    ets.rotate(appliedRotation[0], appliedRotation[1], appliedRotation[2]);

                    ent.setPosition(ent.posX, ent.posY, ent.posZ);
                    double maxWidthHeight = Math.max(ent.width, ent.height);
                    EntityHelper.putEntityWithinAABB(ent, pair.scanRange.addCoord(pair.faceOn.getFrontOffsetX() * -maxWidthHeight, pair.faceOn.getFrontOffsetY() * -maxWidthHeight, pair.faceOn.getFrontOffsetZ() * -maxWidthHeight));

                    ent.motionX = appliedMotion[0];
                    ent.motionY = appliedMotion[1];
                    ent.motionZ = appliedMotion[2];

                    //no going faster than 1 block a tick
                    if(Math.abs(ent.motionX) > 0.99D)
                    {
                        ent.motionX /= Math.abs(ent.motionX) + 0.001D;
                    }
                    if(Math.abs(ent.motionY) > 0.99D)
                    {
                        ent.motionY /= Math.abs(ent.motionY) + 0.001D;
                    }
                    if(Math.abs(ent.motionZ) > 0.99D)
                    {
                        ent.motionZ /= Math.abs(ent.motionZ) + 0.001D;
                    }
                    ent.fallDistance = 0.1F * ((float)ent.motionY / -0.1F * (float)ent.motionY / -0.1F);
                    ent.setPosition(ent.posX, ent.posY, ent.posZ);

                    //transfer over this entity to the other portal.
                    pair.teleportCooldown.put(ent, 3);
                    pair.lastScanEntities.add(ent);
                    WorldPortals.eventHandler.addMonitoredEntity(ent, pair);
                    teleportCooldown.put(ent, 3);
                    lastScanEntities.remove(ent);
                    WorldPortals.eventHandler.removeMonitoredEntity(ent, this);

                    handleSpecialEntities(ent);

                    if(ent.worldObj.isRemote)
                    {
                        handleClientEntityTeleport(ent, appliedRotation);
                    }
                    else
                    {
                        WorldPortals.channel.sendToAllAround(new PacketEntityLocation(ent), new NetworkRegistry.TargetPoint(ent.dimension, ent.posX, ent.posY, ent.posZ, 256D));
                    }
                }
            }
        }

        lastScanEntities.removeAll(entitiesInRange); // now contains entities that are out of the range. Remove this from the tracking.
        for(Entity ent : lastScanEntities)
        {
            WorldPortals.eventHandler.removeMonitoredEntity(ent, this);
        }

        lastScanEntities = entitiesInRange;
    }

    public void handleSpecialEntities(Entity ent)
    {
        if(ent instanceof EntityBlock)
        {
            ((EntityBlock)ent).timeExisting = 2;
        }
        else if(ent instanceof EntityFallingBlock)
        {
            ((EntityFallingBlock)ent).fallTime = 2;
        }
        else if(ent instanceof EntityFireball)
        {
            EntityFireball fireball = (EntityFireball)ent;
            float[] appliedAcceleration = getQuaternionFormula().applyPositionalRotation(new float[] { (float)fireball.accelerationX, (float)fireball.accelerationY, (float)fireball.accelerationZ });
            fireball.accelerationX = appliedAcceleration[0];
            fireball.accelerationY = appliedAcceleration[1];
            fireball.accelerationZ = appliedAcceleration[2];
        }
    }

    public void terminate()
    {
        //TODO notify partner that the partner no longer has a pair as well.
        for(Entity ent : lastScanEntities)
        {
            if(ent.getEntityBoundingBox().intersectsWith(portalInsides))
            {
                EntityHelper.putEntityWithinAABB(ent, flatPlane.offset(faceOn.getFrontOffsetX() * 0.5D, faceOn.getFrontOffsetY() * 0.5D, faceOn.getFrontOffsetZ() * 0.5D));
                ent.setPosition(ent.posX, ent.posY, ent.posZ);
            }
            WorldPortals.eventHandler.removeMonitoredEntity(ent, this);
        }
    }

    public boolean isValid()
    {
        return !firstUpdate;
    }

    public boolean isFirstUpdate()
    {
        return firstUpdate;
    }

    public void forceFirstUpdate()
    {
        firstUpdate = true;
    }

    private AxisAlignedBB createPlaneAround(double size)
    {
        return createPlaneAround(position, size);
    }

    private AxisAlignedBB createPlaneAround(Vec3d pos, double size)
    {
        double halfW = width / 2D;
        double halfH = height / 2D;

        AxisAlignedBB plane = new AxisAlignedBB(pos.xCoord - halfW, pos.yCoord - halfH, pos.zCoord - size, pos.xCoord + halfW, pos.yCoord + halfH, pos.zCoord + size);
        if(faceOn.getAxis() == EnumFacing.Axis.Y)
        {
            plane = EntityHelper.rotateAABB(EnumFacing.Axis.X, plane, faceOn == EnumFacing.UP ? -90F : 90F, pos.xCoord, pos.yCoord, pos.zCoord);
        }
        plane = EntityHelper.rotateAABB(EnumFacing.Axis.Y, plane, faceOn.getAxis() == EnumFacing.Axis.X ? 90F : faceOn.getAxis() == EnumFacing.Axis.Y && upDir.getAxis() == EnumFacing.Axis.X ? 90F : 0F, pos.xCoord, pos.yCoord, pos.zCoord).offset(faceOn.getFrontOffsetX() * getPlaneOffset(), faceOn.getFrontOffsetY() * getPlaneOffset(), faceOn.getFrontOffsetZ() * getPlaneOffset());
        return plane;
    }

    public AxisAlignedBB getCollisionRemovalAabbForEntity(Entity ent)
    {
        double max = Math.max(Math.max(ent.width, ent.height) + Math.sqrt(ent.motionX * ent.motionX + ent.motionY * ent.motionY + ent.motionZ * ent.motionZ), 1D);
        return flatPlane.addCoord(faceOn.getFrontOffsetX() * -max, faceOn.getFrontOffsetY() * -max, faceOn.getFrontOffsetZ() * -max);
    }

    public AxisAlignedBB setupAABBs()
    {
        if(plane == null)
        {
            plane = createPlaneAround(0.0125D);
            flatPlane = createPlaneAround(0);
            scanRange = flatPlane.addCoord(faceOn.getFrontOffsetX() * scanDistance, faceOn.getFrontOffsetY() * scanDistance, faceOn.getFrontOffsetZ() * scanDistance);
            portalInsides = flatPlane.addCoord(faceOn.getFrontOffsetX() * -100D, faceOn.getFrontOffsetY() * -100D, faceOn.getFrontOffsetZ() * -100D);
        }

        return plane;
    }

    public AxisAlignedBB getFlatPlane()
    {
        return flatPlane;
    }

    public AxisAlignedBB getTeleportPlane(float offset)
    {
        if(offset != 0F)
        {
            return flatPlane.offset(faceOn.getFrontOffsetX() * offset, faceOn.getFrontOffsetY() * offset, faceOn.getFrontOffsetZ() * offset);
        }
        return flatPlane;
    }

    public HashSet<AxisAlignedBB> getCollisionBoundaries()
    {
        if(collisions == null)
        {
            collisions = new HashSet<>(4);

            if(canCollideWithBorders())
            {
                double size = 0.0125D;
                setupAABBs();
                AxisAlignedBB plane = flatPlane;

                if(plane.maxX - plane.minX > size * 3D)
                {
                    collisions.add(new AxisAlignedBB(plane.minX, plane.minY, plane.minZ, plane.minX + size, plane.maxY, plane.maxZ));
                    collisions.add(new AxisAlignedBB(plane.maxX - size, plane.minY, plane.minZ, plane.maxX, plane.maxY, plane.maxZ));
                }
                if(plane.maxY - plane.minY > size * 3D)
                {
                    collisions.add(new AxisAlignedBB(plane.minX, plane.minY, plane.minZ, plane.maxX, plane.minY + size, plane.maxZ));
                    collisions.add(new AxisAlignedBB(plane.minX, plane.maxY - size, plane.minZ, plane.maxX, plane.maxY, plane.maxZ));
                }
                if(plane.maxZ - plane.minZ > size * 3D)
                {
                    collisions.add(new AxisAlignedBB(plane.minX, plane.minY, plane.minZ, plane.maxX, plane.maxY, plane.minZ + size));
                    collisions.add(new AxisAlignedBB(plane.minX, plane.minY, plane.maxZ - size, plane.maxX, plane.maxY, plane.maxZ));
                }
            }
        }
        return collisions;
    }

    public boolean hasPair()
    {
        return pair != null && pair.position.yCoord > 0D;
    }

    public void setPair(WorldPortal portal)
    {
        if(pair != portal)
        {
            pair = portal;
            if(pair != null)
            {
                quaternionFormula = QuaternionFormula.createFromPlanes(faceOn, upDir, pair.faceOn, pair.upDir);
            }
        }
    }

    public WorldPortal getPair()
    {
        return pair;
    }

    public void setPosition(Vec3d v)
    {
        this.position = v;
        this.posBlock = new BlockPos(v);
    }

    public Vec3d getPosition() //position of the world portal, pre-offset
    {
        return position;
    }

    public QuaternionFormula getQuaternionFormula()
    {
        return pair != null ? quaternionFormula : QuaternionFormula.NO_ROTATION;
    }

    public NBTTagCompound write(NBTTagCompound tag)
    {
        return writePair(writeSelf(tag));
    }

    public NBTTagCompound writeSelf(NBTTagCompound tag)
    {
        tag.setFloat("width", width);
        tag.setFloat("height", height);

        tag.setInteger("faceOn", faceOn.getIndex());
        tag.setInteger("up", upDir.getIndex());

        tag.setDouble("posX", position.xCoord);
        tag.setDouble("posY", position.yCoord);
        tag.setDouble("posZ", position.zCoord);

        return tag;
    }

    public NBTTagCompound writePair(NBTTagCompound tag)
    {
        if(hasPair())
        {
            tag.setTag("pair", pair.writeSelf(new NBTTagCompound()));
        }
        return tag;
    }

    public void read(NBTTagCompound tag)
    {
        readSelf(tag);
        readPair(tag);
    }

    public void readSelf(NBTTagCompound tag)
    {
        setSize(tag.getFloat("width"), tag.getFloat("height"));
        setFace(EnumFacing.getFront(tag.getInteger("faceOn")), EnumFacing.getFront(tag.getInteger("up")));
        setPosition(new Vec3d(tag.getDouble("posX"), tag.getDouble("posY"), tag.getDouble("posZ")));

        firstUpdate = true;
    }

    public void readPair(NBTTagCompound tag)
    {
        if(tag.hasKey("pair"))
        {
            setPair(createFakeInstance(tag.getCompoundTag("pair")));
        }
    }

    public abstract <T extends WorldPortal> T createFakeInstance(NBTTagCompound tag);

    @SideOnly(Side.CLIENT)
    public void handleClientEntityTeleport(Entity ent, float[] rotations)
    {
        if(ent == Minecraft.getMinecraft().thePlayer)
        {
            WorldPortals.eventHandlerClient.prevCameraRoll = WorldPortals.eventHandlerClient.cameraRoll = rotations[2];
            WorldPortals.channel.sendToServer(new PacketEntityLocation(ent));
        }
    }

    @SideOnly(Side.CLIENT)
    public boolean shouldRenderFront(Entity viewer)
    {
        return true;
    }
}