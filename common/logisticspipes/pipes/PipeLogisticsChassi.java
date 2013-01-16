/** 
 * Copyright (c) Krapht, 2011
 * 
 * "LogisticsPipes" is distributed under the terms of the Minecraft Mod Public 
 * License 1.0, or MMPL. Please check the contents of the license located in
 * http://www.mod-buildcraft.com/MMPL-1.0.txt
 */

package logisticspipes.pipes;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import logisticspipes.LogisticsPipes;
import logisticspipes.gui.GuiChassiPipe;
import logisticspipes.gui.hud.HUDChassiePipe;
import logisticspipes.interfaces.IHeadUpDisplayRenderer;
import logisticspipes.interfaces.IHeadUpDisplayRendererProvider;
import logisticspipes.interfaces.ILegacyActiveModule;
import logisticspipes.interfaces.ILogisticsModule;
import logisticspipes.interfaces.ISendQueueContentRecieiver;
import logisticspipes.interfaces.ISendRoutedItem;
import logisticspipes.interfaces.IWorldProvider;
import logisticspipes.interfaces.routing.IProvideItems;
import logisticspipes.interfaces.routing.IRequestItems;
import logisticspipes.items.ItemModule;
import logisticspipes.logic.BaseChassiLogic;
import logisticspipes.logisticspipes.ChassiModule;
import logisticspipes.logisticspipes.ChassiTransportLayer;
import logisticspipes.logisticspipes.IInventoryProvider;
import logisticspipes.logisticspipes.IRoutedItem;
import logisticspipes.logisticspipes.IRoutedItem.TransportMode;
import logisticspipes.logisticspipes.ItemModuleInformationManager;
import logisticspipes.logisticspipes.SidedInventoryAdapter;
import logisticspipes.logisticspipes.TransportLayer;
import logisticspipes.network.NetworkConstants;
import logisticspipes.network.packets.PacketCoordinates;
import logisticspipes.network.packets.PacketPipeInteger;
import logisticspipes.network.packets.PacketPipeInvContent;
import logisticspipes.network.packets.PacketPipeUpdate;
import logisticspipes.pipefxhandlers.Particles;
import logisticspipes.pipes.basic.RoutedPipe;
import logisticspipes.proxy.MainProxy;
import logisticspipes.proxy.SimpleServiceLocator;
import logisticspipes.request.RequestTreeNode;
import logisticspipes.routing.LogisticsPromise;
import logisticspipes.textures.Textures;
import logisticspipes.textures.Textures.TextureType;
import logisticspipes.utils.ISimpleInventoryEventHandler;
import logisticspipes.utils.InventoryHelper;
import logisticspipes.utils.ItemIdentifier;
import logisticspipes.utils.ItemIdentifierStack;
import logisticspipes.utils.SimpleInventory;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeDirection;
import net.minecraftforge.common.ISidedInventory;
import buildcraft.api.core.Position;
import buildcraft.core.DefaultProps;
import buildcraft.transport.TileGenericPipe;
import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.common.network.Player;

public abstract class PipeLogisticsChassi extends RoutedPipe implements ISimpleInventoryEventHandler, IInventoryProvider, ISendRoutedItem, IProvideItems, IWorldProvider, IHeadUpDisplayRendererProvider, ISendQueueContentRecieiver {

	private final ChassiModule _module;
	private final SimpleInventory _moduleInventory;
	private boolean switchOrientationOnTick = true;
	private boolean init = false;
	BaseChassiLogic ChassiLogic;
	private boolean convertFromMeta = false;
	
	//HUD
	public final LinkedList<ItemIdentifierStack> displayList = new LinkedList<ItemIdentifierStack>();
	public final List<EntityPlayer> localModeWatchers = new ArrayList<EntityPlayer>();
	private HUDChassiePipe HUD;

	public PipeLogisticsChassi(int itemID) {
		super(new BaseChassiLogic(), itemID);
		ChassiLogic = (BaseChassiLogic) logic;
		_moduleInventory = new SimpleInventory(getChassiSize(), "Chassi pipe", 1);
		_moduleInventory.addListener(this);
		_module = new ChassiModule(getChassiSize(), this);
		HUD = new HUDChassiePipe(this, _module, _moduleInventory);
	}
	
	public ForgeDirection getPointedOrientation(){
		return ChassiLogic.orientation;
	}
	
	public TileEntity getPointedTileEntity(){
		if(ChassiLogic.orientation == ForgeDirection.UNKNOWN) return null;
		Position pos = new Position(xCoord, yCoord, zCoord, ChassiLogic.orientation);
		pos.moveForwards(1.0);
		return worldObj.getBlockTileEntity((int)pos.x, (int)pos.y, (int)pos.z);
	}
	
	public void nextOrientation() {
		boolean found = false;
		for (int l = 0; l < 6; ++l) {
			ChassiLogic.orientation = ForgeDirection.values()[(ChassiLogic.orientation.ordinal() + 1) % 6];
			if(isValidOrientation(ChassiLogic.orientation)) {
				found = true;
				break;
			}
		}
		if (!found) {
			ChassiLogic.orientation = ForgeDirection.UNKNOWN;
		}
		MainProxy.sendPacketToAllAround(xCoord, yCoord, zCoord, DefaultProps.NETWORK_UPDATE_RANGE, MainProxy.getDimensionForWorld(worldObj), new PacketPipeUpdate(NetworkConstants.PIPE_UPDATE,xCoord,yCoord,zCoord,getLogisticsNetworkPacket()).getPacket());
		refreshRender(true);
	}
	
	private boolean isValidOrientation(ForgeDirection connection){
		if (connection == ForgeDirection.UNKNOWN) return false;
		if (getRouter().isRoutedExit(connection)) return false;
		Position pos = new Position(xCoord, yCoord, zCoord, connection);
		pos.moveForwards(1.0);
		TileEntity tile = worldObj.getBlockTileEntity((int)pos.x, (int)pos.y, (int)pos.z);

		if (tile == null) return false;
		if (tile instanceof TileGenericPipe) return false;
		return SimpleServiceLocator.buildCraftProxy.checkPipesConnections(this.container, tile);
	}
	
	public IInventory getModuleInventory(){
		return this._moduleInventory;
	}
	
	@Override
	public TextureType getCenterTexture() {
		return Textures.LOGISTICSPIPE_TEXTURE;
	}
	
	@Override
	public TextureType getRoutedTexture(ForgeDirection connection) {
		return Textures.LOGISTICSPIPE_CHASSI_ROUTED_TEXTURE;
	}
	
	@Override
	public TextureType getNonRoutedTexture(ForgeDirection connection) {
		if (connection.equals(ChassiLogic.orientation)){
			return Textures.LOGISTICSPIPE_CHASSI_DIRECTION_TEXTURE;
		}
		return Textures.LOGISTICSPIPE_CHASSI_NOTROUTED_TEXTURE;
	}
	
	@Override
	public void onNeighborBlockChange_Logistics() {
		if (!isValidOrientation(ChassiLogic.orientation)){
			if(MainProxy.isServer(this.worldObj)) {
				nextOrientation();
			}
		}
	};
	
	@Override
	public void onBlockPlaced() {
		super.onBlockPlaced();
		switchOrientationOnTick = true;
	}
	
	
	/*** IInventoryProvider ***/
	
	@Override
	public IInventory getRawInventory() {
		TileEntity tile = getPointedTileEntity();
		if (tile instanceof TileGenericPipe) return null;
		if (!(tile instanceof IInventory)) return null;
		return InventoryHelper.getInventory((IInventory) tile);
	}
	
	@Override
	public IInventory getInventory() {
		IInventory rawInventory = getRawInventory();
		if (rawInventory instanceof ISidedInventory) return new SidedInventoryAdapter((ISidedInventory) rawInventory, this.getPointedOrientation().getOpposite());
		return rawInventory;
	}

	@Override
	public ForgeDirection inventoryOrientation() {
		return getPointedOrientation();
	}
	
	/*** ISendRoutedItem ***/
	
	public java.util.UUID getSourceUUID() {
		return this.getRouter().getId();
	};
	
	@Override
	public void sendStack(ItemStack stack) {
		IRoutedItem itemToSend = SimpleServiceLocator.buildCraftProxy.CreateRoutedItem(stack, this.worldObj);
		//itemToSend.setSource(this.getRouter().getId());
		itemToSend.setTransportMode(TransportMode.Passive);
		super.queueRoutedItem(itemToSend, getPointedOrientation());
	}
	
	@Override
	public void sendStack(ItemStack stack, UUID destination) {
		IRoutedItem itemToSend = SimpleServiceLocator.buildCraftProxy.CreateRoutedItem(stack, this.worldObj);
		itemToSend.setSource(this.getRouter().getId());
		itemToSend.setDestination(destination);
		itemToSend.setTransportMode(TransportMode.Active);
		super.queueRoutedItem(itemToSend, getPointedOrientation());
	}

	@Override
	public void sendStack(ItemStack stack, UUID destination, ItemSendMode mode) {
		IRoutedItem itemToSend = SimpleServiceLocator.buildCraftProxy.CreateRoutedItem(stack, this.worldObj);
		itemToSend.setSource(this.getRouter().getId());
		itemToSend.setDestination(destination);
		itemToSend.setTransportMode(TransportMode.Active);
		super.queueRoutedItem(itemToSend, getPointedOrientation(), mode);
	}

	
	public void readFromNBT(NBTTagCompound nbttagcompound) {
		try {
			super.readFromNBT(nbttagcompound);
			_moduleInventory.readFromNBT(nbttagcompound, "chassi");
			InventoryChanged(_moduleInventory);
			_module.readFromNBT(nbttagcompound);
			ChassiLogic.orientation = ForgeDirection.values()[nbttagcompound.getInteger("Orientation") % 7];
			if(nbttagcompound.getInteger("Orientation") == 0) {
				convertFromMeta = true;
			}
			switchOrientationOnTick = false;
		} catch(Exception e) {
			e.printStackTrace();
		}
	}
	
	@Override
	public void writeToNBT(NBTTagCompound nbttagcompound) {
		super.writeToNBT(nbttagcompound);
		_moduleInventory.writeToNBT(nbttagcompound, "chassi");
		_module.writeToNBT(nbttagcompound);
		nbttagcompound.setInteger("Orientation", ChassiLogic.orientation.ordinal());
	}

	@Override
	public void onBlockRemoval() {
		super.onBlockRemoval();
		_moduleInventory.removeListener(this);
		if(MainProxy.isServer(this.worldObj)) {
			for(int i=0;i<_moduleInventory.getSizeInventory();i++) {
				if(_moduleInventory.getStackInSlot(i) != null) {
					ItemModuleInformationManager.saveInfotmation(_moduleInventory.getStackInSlot(i), this.getLogisticsModule().getSubModule(i));
				}
			}
			_moduleInventory.dropContents(this.worldObj, this.xCoord, this.yCoord, this.zCoord);
		}
	}

	@Override
	public void InventoryChanged(SimpleInventory inventory) {
		boolean reInitGui = false;
		for (int i = 0; i < inventory.getSizeInventory(); i++){
			ItemStack stack = inventory.getStackInSlot(i);
			if (stack == null){
				if (_module.hasModule(i)){
					_module.removeModule(i);
					reInitGui = true;
				}
				continue;
			}
			
			if (stack.getItem() instanceof ItemModule){
				ILogisticsModule current = _module.getModule(i);
				ILogisticsModule next = ((ItemModule)stack.getItem()).getModuleForItem(stack, _module.getModule(i), this, this, this, this);
				next.registerPosition(xCoord, yCoord, zCoord, i);
				if (current != next){
					_module.installModule(i, next);
					if(!MainProxy.isClient()) {
						ItemModuleInformationManager.readInformation(stack, next);
					}
					ItemModuleInformationManager.removeInformation(stack);
				}
			}
		}
		if (reInitGui) {
			if(MainProxy.isClient(this.worldObj)) {
				if (FMLClientHandler.instance().getClient().currentScreen instanceof GuiChassiPipe){
					FMLClientHandler.instance().getClient().currentScreen.initGui();
				}
			}
		}
		if(MainProxy.isServer()) {
			MainProxy.sendToPlayerList(new PacketPipeInvContent(NetworkConstants.CHASSIE_PIPE_MODULE_CONTENT, xCoord, yCoord, zCoord, ItemIdentifierStack.getListFromInventory(_moduleInventory)).getPacket(), localModeWatchers);
		}
	}

	@Override
	public void ignoreDisableUpdateEntity() {
		if (switchOrientationOnTick){
			switchOrientationOnTick = false;
			if(MainProxy.isServer(this.worldObj)) {
				nextOrientation();
			}
		}
		if(convertFromMeta && worldObj.getBlockMetadata(xCoord, yCoord, zCoord) != 0) {
			ChassiLogic.orientation = ForgeDirection.values()[worldObj.getBlockMetadata(xCoord, yCoord, zCoord) % 6];
			worldObj.setBlockMetadata(xCoord, yCoord, zCoord, 0);
		}
		if(!init) {
			init = true;
			if(MainProxy.isClient(this.worldObj)) {
				MainProxy.sendPacketToServer(new PacketCoordinates(NetworkConstants.REQUEST_PIPE_UPDATE, xCoord, yCoord, zCoord).getPacket());
			}
		}
	}
	
	public abstract int getChassiSize();
	
	@Override
	public final ILogisticsModule getLogisticsModule() {
		return _module;
	}
	
	@Override
	public TransportLayer getTransportLayer() {
		if (this._transportLayer == null){
			_transportLayer = new ChassiTransportLayer(this);
		}
		return _transportLayer;
	}
	
	private boolean tryInsertingModule(EntityPlayer entityplayer) {
		if(MainProxy.isClient()) return false;
		if(entityplayer.getCurrentEquippedItem().itemID == LogisticsPipes.ModuleItem.itemID) {
			if(entityplayer.getCurrentEquippedItem().getItemDamage() != ItemModule.BLANK) {
				for(int i=0;i<_moduleInventory.getSizeInventory();i++) {
					ItemStack item = _moduleInventory.getStackInSlot(i);
					if(item == null) {
						_moduleInventory.setInventorySlotContents(i, entityplayer.getCurrentEquippedItem().splitStack(1));
						InventoryChanged(_moduleInventory);
						return true;
					}
				}
			}
		}
		return false;
	}
	
	@Override
	public boolean blockActivated(World world, int x, int y, int z,	EntityPlayer entityplayer) {
		if (entityplayer.getCurrentEquippedItem() == null) return super.blockActivated(world, x, y, z, entityplayer);
		
		if (SimpleServiceLocator.buildCraftProxy.isWrenchEquipped(entityplayer)) {
			if (entityplayer.isSneaking()){
				if(MainProxy.isServer(this.worldObj)) {
					((PipeLogisticsChassi)this.container.pipe).nextOrientation();
				}
				return true;
			}
		}

		if(tryInsertingModule(entityplayer)) {
			return true;
		}
		
		return super.blockActivated(world, x, y, z, entityplayer);
	}
	
	/*** IProvideItems ***/
	@Override
	public void canProvide(RequestTreeNode tree, Map<ItemIdentifier, Integer> donePromisses) {
		
		if (!isEnabled()){
			return;
		}
		
		for (int i = 0; i < this.getChassiSize(); i++){
			ILogisticsModule x = _module.getSubModule(i);
			if (x instanceof ILegacyActiveModule){
				((ILegacyActiveModule)x).canProvide(tree, donePromisses);
			}
		}
	}
	
	@Override
	public void fullFill(LogisticsPromise promise, IRequestItems destination) {
		if (!isEnabled()){
			return;
		}
		for (int i = 0; i < this.getChassiSize(); i++){
			ILogisticsModule x = _module.getSubModule(i);
			if (x instanceof ILegacyActiveModule){
				((ILegacyActiveModule)x).fullFill(promise, destination);
				MainProxy.sendSpawnParticlePacket(Particles.VioletParticle, xCoord, yCoord, this.zCoord, this.worldObj, 2);
			}
		}
	}
	
	@Override
	public int getAvailableItemCount(ItemIdentifier item) {
		if (!isEnabled()){
			return 0;
		}
		
		for (int i = 0; i < this.getChassiSize(); i++){
			ILogisticsModule x = _module.getSubModule(i);
			if (x instanceof ILegacyActiveModule){
				return ((ILegacyActiveModule)x).getAvailableItemCount(item);
			}
		}
		return 0;
	}
	
	@Override
	public void getAllItems(Map<UUID, Map<ItemIdentifier, Integer>> list) {
		if (!isEnabled()){
			return;
		}
		for (int i = 0; i < this.getChassiSize(); i++){
			ILogisticsModule x = _module.getSubModule(i);
			if (x instanceof ILegacyActiveModule) {
				((ILegacyActiveModule)x).getAllItems(list);
				return;
			}
		}
	}
	
	@Override
	public ItemSendMode getItemSendMode() {
		return ItemSendMode.Normal;
	}
	
	@Override
	public World getWorld() {
		return this.worldObj;
	}

	@Override
	public IHeadUpDisplayRenderer getRenderer() {
		return HUD;
	}

	@Override
	public int getX() {
		return xCoord;
	}

	@Override
	public int getY() {
		return yCoord;
	}

	@Override
	public int getZ() {
		return zCoord;
	}

	@Override
	public void startWaitching() {
		MainProxy.sendPacketToServer(new PacketPipeInteger(NetworkConstants.HUD_START_WATCHING, xCoord, yCoord, zCoord, 1).getPacket());
	}

	@Override
	public void stopWaitching() {
		MainProxy.sendPacketToServer(new PacketPipeInteger(NetworkConstants.HUD_STOP_WATCHING, xCoord, yCoord, zCoord, 1).getPacket());
		HUD.stopWatching();
	}

	@Override
	public void playerStartWatching(EntityPlayer player, int mode) {
		if(mode == 1) {
			localModeWatchers.add(player);
			MainProxy.sendPacketToPlayer(new PacketPipeInvContent(NetworkConstants.CHASSIE_PIPE_MODULE_CONTENT, xCoord, yCoord, zCoord, ItemIdentifierStack.getListFromInventory(_moduleInventory)).getPacket(), (Player)player);
			MainProxy.sendPacketToPlayer(new PacketPipeInvContent(NetworkConstants.SEND_QUEUE_CONTENT, xCoord, yCoord, zCoord, ItemIdentifierStack.getListSendQueue(_sendQueue)).getPacket(), (Player)player);
		} else {
			super.playerStartWatching(player, mode);
		}
	}

	@Override
	public void playerStopWatching(EntityPlayer player, int mode) {
		super.playerStopWatching(player, mode);
		localModeWatchers.remove(player);
	}

	public void handleModuleItemIdentifierList(LinkedList<ItemIdentifierStack> _allItems) {
		_moduleInventory.handleItemIdentifierList(_allItems);
	}

	public void handleContentItemIdentifierList(LinkedList<ItemIdentifierStack> _allItems) {
		_moduleInventory.handleItemIdentifierList(_allItems);
	}

	@Override
	protected void sendQueueChanged() {
		if(MainProxy.isServer()) {
			MainProxy.sendToPlayerList(new PacketPipeInvContent(NetworkConstants.SEND_QUEUE_CONTENT, xCoord, yCoord, zCoord, ItemIdentifierStack.getListSendQueue(_sendQueue)).getPacket(), localModeWatchers);
		}
	}

	public void handleSendQueueItemIdentifierList(LinkedList<ItemIdentifierStack> _allItems){
		displayList.clear();
		displayList.addAll(_allItems);
	}
	
	public ChassiModule getModules() {
		return _module;
	}

	@Override
	public void setTile(TileEntity tile) {
		super.setTile(tile);
		for (int i = 0; i < _moduleInventory.getSizeInventory(); i++){
			ILogisticsModule current = _module.getModule(i);
			if(current != null) {
				current.registerPosition(xCoord, yCoord, zCoord, i);
			}
		}
	}
}
