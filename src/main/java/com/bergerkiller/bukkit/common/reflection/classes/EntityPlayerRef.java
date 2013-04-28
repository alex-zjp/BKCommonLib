package com.bergerkiller.bukkit.common.reflection.classes;

import java.util.List;

import org.bukkit.entity.Player;

import com.bergerkiller.bukkit.common.conversion.Conversion;
import com.bergerkiller.bukkit.common.reflection.ClassTemplate;
import com.bergerkiller.bukkit.common.reflection.FieldAccessor;
import com.bergerkiller.bukkit.common.reflection.NMSClassTemplate;
import com.bergerkiller.bukkit.common.reflection.SafeField;
import com.bergerkiller.bukkit.common.utils.CommonUtil;

public class EntityPlayerRef extends EntityHumanRef {
	public static final ClassTemplate<Object> TEMPLATE = new NMSClassTemplate("EntityPlayer");
	public static final FieldAccessor<List<?>> chunkQueue = TEMPLATE.getField("chunkCoordIntPairQueue");
	public static final FieldAccessor<Object> playerConnection = TEMPLATE.getField("playerConnection");
	public static final FieldAccessor<Boolean> disconnected = new SafeField<Boolean>(CommonUtil.getNMSClass("PlayerConnection"), "disconnected");

	public static Object getNetworkManager(Player player) {
		Object conn = playerConnection.get(Conversion.toEntityHandle.convert(player));
		return conn == null ? null : PlayerConnectionRef.networkManager.get(conn);
	}
}
