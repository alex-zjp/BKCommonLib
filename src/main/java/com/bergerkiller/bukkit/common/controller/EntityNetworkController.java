package com.bergerkiller.bukkit.common.controller;

import java.util.Collection;
import java.util.Collections;

import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import net.minecraft.server.v1_5_R1.Entity;

import com.bergerkiller.bukkit.common.bases.IntVector2;
import com.bergerkiller.bukkit.common.bases.IntVector3;
import com.bergerkiller.bukkit.common.conversion.Conversion;
import com.bergerkiller.bukkit.common.entity.CommonEntity;
import com.bergerkiller.bukkit.common.entity.CommonEntityController;
import com.bergerkiller.bukkit.common.entity.CommonEntityType;
import com.bergerkiller.bukkit.common.entity.CommonEntityTypeStore;
import com.bergerkiller.bukkit.common.entity.nms.NMSEntityTrackerEntry;
import com.bergerkiller.bukkit.common.internal.CommonNMS;
import com.bergerkiller.bukkit.common.protocol.CommonPacket;
import com.bergerkiller.bukkit.common.protocol.PacketFields;
import com.bergerkiller.bukkit.common.reflection.classes.EntityTrackerEntryRef;
import com.bergerkiller.bukkit.common.utils.EntityUtil;
import com.bergerkiller.bukkit.common.utils.MathUtil;
import com.bergerkiller.bukkit.common.utils.PacketUtil;
import com.bergerkiller.bukkit.common.utils.PlayerUtil;
import com.bergerkiller.bukkit.common.wrappers.DataWatcher;

/**
 * A controller that deals with the server to client network synchronization.
 * Always make sure that the methods in this controller are synchronized.
 * The methods can be called from Threads other than the main Thread.
 * For this reason, avoid calling methods from the main thread. Use the provided
 * {@link onSync} method instead.
 * 
 * @param <T> - type of Common Entity this controller is for
 */
public class EntityNetworkController<T extends CommonEntity<?>> extends CommonEntityController<T> {
	/**
	 * The maximum allowed distance per relative movement update
	 */
	public static final int MAX_RELATIVE_DISTANCE = 127;
	/**
	 * The minimum value change that is able to trigger an update
	 */
	public static final int MIN_RELATIVE_CHANGE = 4;
	/**
	 * The minimum velocity change that is able to trigger an update
	 */
	public static final double MIN_RELATIVE_VELOCITY = 0.02;
	/**
	 * The minimum velocity change that is able to trigger an update (squared)
	 */
	public static final double MIN_RELATIVE_VELOCITY_SQUARED = MIN_RELATIVE_VELOCITY * MIN_RELATIVE_VELOCITY;

	private NMSEntityTrackerEntry handle;

	/**
	 * Obtains the Entity Tracker Entry handle of this Network Controller
	 * 
	 * @return entry handle
	 */
	public Object getHandle() {
		return handle;
	}

	/**
	 * Erases all previously set settings and sets the default settings
	 * for the kind of Entity this controller is attached to.<br><br>
	 * 
	 * This method is called prior to {@link onAttached()}.
	 */
	public final void setDefaultSettings() {
		final CommonEntityType type = CommonEntityTypeStore.byEntity(entity.getEntity());
		if (handle == null) {
			handle = new NMSEntityTrackerEntry(entity.getHandle(Entity.class), type.networkViewDistance, type.networkUpdateInterval, type.networkIsMobile);
		} else {
			setViewDistance(type.networkViewDistance);
			setUpdateInterval(type.networkUpdateInterval);
			setMobile(type.networkIsMobile);
		}
	}

	/**
	 * Gets a collection of all Players viewing this Entity
	 * 
	 * @return viewing players
	 */
	public synchronized final Collection<Player> getViewers() {
		return Collections.unmodifiableCollection(CommonNMS.getPlayers(handle.trackedPlayers));
	}

	/**
	 * Adds a new viewer to this Network Controller.
	 * Calling this method also results in spawn messages being sent to the viewer.
	 * 
	 * @param viewer to add
	 * @return True if the viewer was added, False if the viewer was already added
	 */
	@SuppressWarnings("unchecked")
	public synchronized boolean addViewer(Player viewer) {
		if (!handle.trackedPlayers.add(Conversion.toEntityHandle.convert(viewer))) {
			return false;
		}
		this.makeVisible(viewer);
		return true;
	}

	/**
	 * Removes a viewer from this Network Controller.
	 * Calling this method also results in destroy messages beint sent to the viewer.
	 * 
	 * @param viewer to remove
	 * @return True if the viewer was removed, False if the viewer wasn't contained
	 */
	public synchronized boolean removeViewer(Player viewer) {
		if (!handle.trackedPlayers.remove(Conversion.toEntityHandle.convert(viewer))) {
			return false;
		}
		this.makeHidden(viewer);
		return true;
	}

	/**
	 * Adds or removes a viewer based on viewer distance
	 * 
	 * @param viewer to update
	 */
	public synchronized final void updateViewer(Player viewer) {
		final IntVector3 pos = this.getProtocolPositionSynched();
		final double dx = Math.abs(EntityUtil.getLocX(viewer) - (double) (pos.x / 32.0));
		final double dz = Math.abs(EntityUtil.getLocZ(viewer) - (double) (pos.z / 32.0));
		final double view = this.getViewDistance();
		if (dx <= view && dz <= view && PlayerUtil.isChunkEntered(viewer, entity.getChunkX(), entity.getChunkZ())) {
			addViewer(viewer);
		} else {
			removeViewer(viewer);
		}
	}

	/**
	 * Ensures that the Entity is displayed to the viewer
	 * 
	 * @param viewer to display this Entity for
	 */
	public synchronized void makeVisible(Player viewer) {
		CommonNMS.getNative(viewer).removeQueue.remove(entity.getEntityId());

		// Spawn packet
		PacketUtil.sendCommonPacket(viewer, getSpawnPacket());

		// Meta Data
		PacketUtil.sendPacket(viewer, PacketFields.ENTITY_METADATA.newInstance(entity.getEntityId(), entity.getMetaData(), true));

		// Velocity
		PacketUtil.sendPacket(viewer, PacketFields.ENTITY_VELOCITY.newInstance(entity.getEntityId(), this.getProtocolVelocitySynched()));

		// Passenger?
		if (entity.isInsideVehicle()) {
			PacketUtil.sendPacket(viewer, PacketFields.ATTACH_ENTITY.newInstance(entity.getEntity(), entity.getVehicle()));
		}

		// Special living entity messages
		if (entity.getEntity() instanceof LivingEntity) {
			// Equipment: TODO (needs NMS because index is lost...)

			// Mob effects: TODO (can use Potion effects?)
		}

		// Human entity sleeping action
		if (entity.getEntity() instanceof HumanEntity && ((HumanEntity) entity.getEntity()).isSleeping()) {
			PacketUtil.sendPacket(viewer, PacketFields.ENTITY_LOCATION_ACTION.newInstance(entity.getEntityId(), 
					0, entity.getLocBlockX(), entity.getLocBlockY(), entity.getLocBlockZ()));
		}

		// Initial entity head rotation
		int headRot = getProtocolHeadRotation();
		if (headRot != 0) {
			PacketUtil.sendPacket(viewer, PacketFields.ENTITY_HEAD_ROTATION.newInstance(entity.getEntityId(), (byte) headRot));
		}
	}

	/**
	 * Ensures that the Entity is no longer displayed to any viewers.
	 * All viewers will see the Entity disappear. This method queues for the next tick.
	 */
	public synchronized void makeHiddenForAll() {
		for (Player viewer : getViewers()) {
			makeHidden(viewer);
		}
	}

	/**
	 * Ensures that the Entity is no longer displayed to the viewer.
	 * The entity is not instantly hidden; it is queued for the next tick.
	 * 
	 * @param viewer to hide this Entity for
	 */
	public synchronized void makeHidden(Player viewer) {
		makeHidden(viewer, false);
	}

	/**
	 * Ensures that the Entity is no longer displayed to the viewer
	 * 
	 * @param viewer to hide this Entity for
	 * @param instant option: True to instantly hide, False to queue it for the next tick
	 */
	@SuppressWarnings("unchecked")
	public synchronized void makeHidden(Player viewer, boolean instant) {
		if (instant) {
			PacketUtil.sendPacket(viewer, PacketFields.DESTROY_ENTITY.newInstance(entity.getEntityId()));
		} else {
			CommonNMS.getNative(viewer).removeQueue.add(entity.getEntityId());
		}
	}

	/**
	 * Called at a set interval to synchronize data to clients
	 */
	public synchronized void onSync() {
		//TODO: Item frame support? Meh. Not for not. Later.
		this.syncVehicle();
		if (this.isTick(this.getUpdateInterval()) || entity.isPositionChanged()) {
			this.syncLocation();
			this.syncVelocity();
		}
		this.syncMeta();
		this.syncHeadYaw();
	}

	/**
	 * Synchronizes the entity Vehicle.
	 * Updates when the vehicle changes, or if in a Vehicle at a set interval.
	 */
	public synchronized void syncVehicle() {
		org.bukkit.entity.Entity oldVehicle = this.getVehicleSynched();
		org.bukkit.entity.Entity newVehicle = entity.getVehicle();
		if (oldVehicle != newVehicle || (newVehicle != null && isTick(60))) {
			this.syncVehicle(newVehicle);
		}
	}

	/**
	 * Synchronizes the entity location to all clients.
	 * Based on the distances, relative or absolute movement is performed.
	 */
	public synchronized void syncLocation() {
		// Position
		final IntVector3 oldPos = this.getProtocolPositionSynched();
		final IntVector3 newPos = this.getProtocolPosition();
		final boolean moved = newPos.subtract(oldPos).abs().greaterEqualThan(MIN_RELATIVE_CHANGE);
		// Rotation
		final IntVector2 oldRot = this.getProtocolRotationSynched();
		final IntVector2 newRot = this.getProtocolRotation();
		final boolean rotated = newRot.subtract(oldRot).abs().greaterEqualThan(MIN_RELATIVE_CHANGE);
		// Synchronize
		syncLocation(moved ? newPos : oldPos, rotated ? newRot : oldRot);
	}

	/**
	 * Synchronizes the entity head yaw rotation to all Clients.
	 */
	public synchronized void syncHeadYaw() {
		final int oldYaw = this.getProtocolHeadRotationSynched();
		final int newYaw = this.getProtocolHeadRotation();
		if (Math.abs(newYaw - oldYaw) >= MIN_RELATIVE_CHANGE) {
			syncHeadYaw(newYaw);
		}
	}

	/**
	 * Synchronizes the entity metadata to all Clients.
	 * Metadata changes are read and used.
	 */
	public synchronized void syncMeta() {
		DataWatcher meta = entity.getMetaData();
		if (meta.isChanged()) {
			broadcast(new CommonPacket(PacketFields.ENTITY_METADATA.newInstance(entity.getEntityId(), meta, false)), true);
		}
	}

	/**
	 * Synchronizes the entity velocity to all Clients.
	 * Based on a change in Velocity, velocity will be updated.
	 */
	public synchronized void syncVelocity() {
		if (!this.isMobile()) {
			return;
		}
		//TODO: For players, there should be an event here!
		Vector oldVel = this.getProtocolVelocitySynched();
		Vector newVel = this.getProtocolVelocity();
		// Synchronize velocity when the entity stopped moving, or when the velocity change is large enough
		if ((newVel.lengthSquared() == 0.0 && oldVel.lengthSquared() > 0.0) || oldVel.distanceSquared(newVel) > MIN_RELATIVE_VELOCITY_SQUARED) {
			this.syncVelocity(newVel);
		}
	}

	/**
	 * Sends a packet to all viewers, excluding the entity itself
	 * 
	 * @param packet to send
	 */
	public synchronized void broadcast(CommonPacket packet) {
		broadcast(packet, false);
	}

	/**
	 * Sends a packet to all viewers, and if set, to itself
	 * 
	 * @param packet to send
	 * @param self option: True to send to self (if a player), False to not send to self
	 */
	public synchronized void broadcast(CommonPacket packet, boolean self) {
		if (self && entity.getEntity() instanceof Player) {
			PacketUtil.sendPacket((Player) entity.getEntity(), packet);
		}
		// Viewers
		for (Player viewer : this.getViewers()) {
			PacketUtil.sendPacket(viewer, packet);
		}
	}

	/**
	 * Creates a new spawn packet for spawning this Entity.
	 * To change the spawned entity type, override this method.
	 * By default, the entity is evaluated and the right packet is created automatically.
	 * 
	 * @return spawn packet
	 */
	public synchronized CommonPacket getSpawnPacket() {
		final Object packet = EntityTrackerEntryRef.getSpawnPacket(handle);
		if (PacketFields.VEHICLE_SPAWN.isInstance(packet)) {
			// NMS error: They are not using the synchronized position, but the live position
			// This has some big issues when new players join...

			// Position
			final IntVector3 pos = this.getProtocolPositionSynched();
			PacketFields.VEHICLE_SPAWN.x.set(packet, pos.x);
			PacketFields.VEHICLE_SPAWN.y.set(packet, pos.y);
			PacketFields.VEHICLE_SPAWN.z.set(packet, pos.z);
			// Rotation
			final IntVector2 rot = this.getProtocolRotationSynched();
			PacketFields.VEHICLE_SPAWN.yaw.set(packet, (byte) rot.x);
			PacketFields.VEHICLE_SPAWN.pitch.set(packet, (byte) rot.z);
		}
		return new CommonPacket(packet);
	}

	public synchronized int getViewDistance() {
		return handle.b;
	}

	public synchronized void setViewDistance(int blockDistance) {
		handle.b = blockDistance;
	}

	public synchronized int getUpdateInterval() {
		return handle.c;
	}

	public synchronized void setUpdateInterval(int tickInterval) {
		handle.c = tickInterval;
	}

	public synchronized boolean isMobile() {
		return EntityTrackerEntryRef.isMobile.get(handle);
	}

	public synchronized void setMobile(boolean mobile) {
		EntityTrackerEntryRef.isMobile.set(handle, mobile);
	}

	/**
	 * Synchronizes the entity Vehicle
	 * 
	 * @param vehicle to synchronize, NULL for no Vehicle
	 */
	public synchronized void syncVehicle(org.bukkit.entity.Entity vehicle) {
		EntityTrackerEntryRef.vehicle.set(handle, vehicle);
		broadcast(new CommonPacket(PacketFields.ATTACH_ENTITY.newInstance(entity.getEntity(), vehicle)));
	}

	/**
	 * Synchronizes the entity position / rotation.
	 * 
	 * @param position (new)
	 * @param rotation (new, x = yaw, z = pitch)
	 */
	public synchronized void syncLocation(IntVector3 position, IntVector2 rotation) {
		final IntVector3 deltaPos = position.subtract(this.getProtocolPositionSynched());
		final IntVector2 deltaRot = rotation.subtract(this.getProtocolRotationSynched());
		final Object packet;
		final boolean moved, rotated;
		if (deltaPos.greaterThan(MAX_RELATIVE_DISTANCE)) {
			// Perform teleport instead
			packet = PacketFields.ENTITY_TELEPORT.newInstance(entity.getEntityId(), position.x, position.y, position.z, 
					(byte) rotation.x, (byte) rotation.z);

			moved = rotated = true;
		} else {
			// Create a proper relative move/look packet based on the change
			moved = !deltaPos.equals(IntVector3.ZERO);
			rotated = !deltaRot.equals(IntVector2.ZERO);

			// If inside vehicle - there is no use to update the location!
			if (entity.isInsideVehicle()) {
				if (rotated) {
					packet = PacketFields.ENTITY_LOOK.newInstance(entity.getEntityId(), (byte) rotation.x, (byte) rotation.z);
				} else {
					packet = null;
				}
			} else if (moved && rotated) {
				packet = PacketFields.REL_ENTITY_MOVE_LOOK.newInstance(entity.getEntityId(), 
						(byte) deltaPos.x, (byte) deltaPos.y, (byte) deltaPos.z, (byte) rotation.x, (byte) rotation.z);

			} else if (moved) {
				packet = PacketFields.REL_ENTITY_MOVE.newInstance(entity.getEntityId(), 
						(byte) deltaPos.x, (byte) deltaPos.y, (byte) deltaPos.z);

			} else if (rotated) {
				packet = PacketFields.ENTITY_LOOK.newInstance(entity.getEntityId(), (byte) rotation.x, (byte) rotation.z);
			} else {
				return;
			}
		}

		// Update protocol values
		if (moved) {
			handle.xLoc = position.x;
			handle.yLoc = position.y;
			handle.zLoc = position.z;
		}
		if (rotated) {
			handle.yRot = rotation.x;
			handle.xRot = rotation.z;
		}

		// Send the produced packet
		if (packet != null) {
			broadcast(new CommonPacket(packet));
		}
	}

	/**
	 * Synchronizes the entity head yaw rotation to all Clients
	 * 
	 * @param headRotation to set to
	 */
	public synchronized void syncHeadYaw(int headRotation) {
		handle.i = headRotation;
		this.broadcast(new CommonPacket(PacketFields.ENTITY_HEAD_ROTATION.newInstance(entity.getEntityId(), (byte) headRotation)));
	}

	/**
	 * Synchronizes the entity velocity
	 * 
	 * @param velocity (new)
	 */
	public synchronized void syncVelocity(Vector velocity) {
		handle.j = velocity.getX();
		handle.k = velocity.getY();
		handle.l = velocity.getZ();
		// If inside a vehicle, there is no use in updating
		if (entity.isInsideVehicle()) {
			return;
		}
		this.broadcast(new CommonPacket(PacketFields.ENTITY_VELOCITY.newInstance(entity.getEntityId(), velocity)));
	}

	/**
	 * Obtains the current Vehicle entity according to the viewers of this entity
	 * 
	 * @return Client-synchronized vehicle entity
	 */
	public synchronized org.bukkit.entity.Entity getVehicleSynched() {
		return EntityTrackerEntryRef.vehicle.get(handle);
	}

	/**
	 * Obtains the current velocity of the entity according to the viewers of this entity
	 * 
	 * @return Client-synchronized entity velocity
	 */
	public synchronized Vector getProtocolVelocitySynched() {
		return new Vector(handle.j, handle.k, handle.l);
	}

	/**
	 * Obtains the current position of the entity according to the viewers of this entity
	 * 
	 * @return Client-synchronized entity position
	 */
	public synchronized IntVector3 getProtocolPositionSynched() {
		return new IntVector3(handle.xLoc, handle.yLoc, handle.zLoc);
	}

	/**
	 * Obtains the current rotation of the entity according to the viewers of this entity
	 * 
	 * @return Client-synchronized entity rotation (x = yaw, z = pitch)
	 */
	public synchronized IntVector2 getProtocolRotationSynched() {
		return new IntVector2(handle.yRot, handle.xRot);
	}

	/**
	 * Obtains the current velocity of the entity, converted to protocol format
	 * 
	 * @return Entity velocity in protocol format
	 */
	public Vector getProtocolVelocity() {
		return this.entity.getVelocity();
	}

	/**
	 * Obtains the current position of the entity, converted to protocol format
	 * 
	 * @return Entity position in protocol format
	 */
	public IntVector3 getProtocolPosition() {
		final Entity entity = this.entity.getHandle(Entity.class);
		return new IntVector3(handle.protLoc(entity.locX), MathUtil.floor(entity.locY * 32.0), handle.protLoc(entity.locZ));
	}

	/**
	 * Obtains the current rotation (yaw/pitch) of the entity, converted to protocol format
	 * 
	 * @return Entity rotation in protocol format (x = yaw, z = pitch)
	 */
	public IntVector2 getProtocolRotation() {
		final Entity entity = this.entity.getHandle(Entity.class);
		return new IntVector2(handle.protRot(entity.yaw), handle.protRot(entity.pitch));
	}

	/**
	 * Obtains the current head yaw rotation of this entity, according to the viewers
	 * 
	 * @return Client-synched head-yaw rotation
	 */
	public synchronized int getProtocolHeadRotationSynched() {
		return handle.i;
	}

	/**
	 * Checks whether a certain interval is reached
	 * 
	 * @param interval in ticks
	 * @return True if the interval was reached, False if not
	 */
	public synchronized boolean isTick(int interval) {
		return (handle.m % interval) == 0;
	}

	/**
	 * Obtains the current 'tick' value, which can be used for intervals
	 * 
	 * @return Tick time
	 */
	public synchronized int getTick() {
		return handle.m;
	}

	/**
	 * Obtains the current head rotation of the entity, converted to protocol format
	 * 
	 * @return Entity head rotation in protocol format
	 */
	public int getProtocolHeadRotation() {
		return handle.protRot(this.entity.getHeadRotation());
	}
}