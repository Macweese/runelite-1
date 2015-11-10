package net.runelite.deob.attributes.code;

import net.runelite.deob.attributes.Code;
import net.runelite.deob.attributes.code.instruction.types.JumpingInstruction;
import net.runelite.deob.block.Block;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import net.runelite.deob.attributes.code.instructions.Goto;

public class Instructions
{
	private Code code;
	private List<Instruction> instructions = new ArrayList<>();
	private List<Block> blocks = new ArrayList<>();

	public Instructions(Code code)
	{
		this.code = code;
	}
	
	public void load(DataInputStream is) throws IOException
	{
		int length = is.readInt();

		int pc;
		for (pc = 0; pc < length;)
		{
			byte opcode = is.readByte();

			InstructionType type = InstructionType.findInstructionFromCode(opcode);

			try
			{
				Constructor<? extends Instruction> con = type.getInstructionClass().getConstructor(Instructions.class, InstructionType.class, int.class);
				Instruction ins = con.newInstance(this, type, pc);
				ins.load(is);
				
				Instruction genericIns = ins.makeGeneric();
				if (genericIns != ins)
				{
					genericIns.setPc(ins.getPc());
				}

				instructions.add(genericIns);

				int len = ins.getLength();
				pc += len;
			}
			catch (java.lang.Exception ex)
			{
				throw new IOException(ex);
			}
		}

		assert pc == length;
		
		for (Instruction i : instructions)
			i.resolve();
	}
	
	public List<Instruction> getInstructions()
	{
		return instructions;
	}
	
	public void addInstruction(Instruction i)
	{
		instructions.add(i);
	}
	
	public List<Block> getBlocks()
	{
		return blocks;
	}
	
	public void remove(Instruction ins)
	{
		assert ins.getInstructions() == this;
		ins.remove();
		instructions.remove(ins);
		ins.setInstructions(null);
	}
	
	public void remove(Block block)
	{
		blocks.remove(block);
		
		for (Instruction i : block.instructions)
		{
			i.block = null;
			remove(i);
		}
	}
	
	private void findExceptionInfo(Block block, Instruction i)
	{
		for (Exception e : code.getExceptions().getExceptions())
		{
			int startIdx = instructions.indexOf(e.getStart()),
					endIdx = instructions.indexOf(e.getEnd()),
					thisIdx = instructions.indexOf(i);
			
			assert startIdx != -1;
			assert endIdx != -1;
			assert thisIdx != -1;
			
			assert endIdx > startIdx;
			
			if (thisIdx >= startIdx && thisIdx < endIdx)
			{
				if (!block.exceptions.contains(e))
					block.exceptions.add(e);
			}
			if (e.getHandler() == i)
			{
				block.handlers.add(e);
			}
		}
	}
	
	private boolean isException(Instruction i)
	{
		for (Exception e : code.getExceptions().getExceptions())
			if (e.getHandler() == i || e.getStart() == i)
				return true;
		return false;
	}
	
	private boolean isExceptionEnd(Instruction i)
	{
		for (Exception e : code.getExceptions().getExceptions())
			if (e.getEnd() ==  i)
				return true;
		return false;
	}
	
	public void buildBlocks()
	{
		clearBlockGraph();
		buildJumpGraph();
		
		Block current = null;
		for (int j = 0; j < instructions.size(); ++j)
		{
			Instruction i = instructions.get(j),
					next = j + 1 < instructions.size() ? instructions.get(j + 1) : null;

			if (current == null)
			{
				current = new Block();
				current.begin = i;
			}
			
			current.instructions.add(i);
			findExceptionInfo(current, i);
			
			if (i.isTerminal() || next == null || isException(next) || isExceptionEnd(i) || !next.from.isEmpty())
			{
				current.end = i;
				blocks.add(current);
				current = null;
			}
		}
	}
	
	public void clearBlockGraph()
	{
		for (Instruction i : instructions)
			i.block = null;
		blocks.clear();
	}
	
	public void write(DataOutputStream out) throws IOException
	{
		// trnaslate instructions to specific
		this.buildJumpGraph();
		
		for (Instruction i : new ArrayList<>(instructions))
		{
			Instruction specific = i.makeSpecific();
			if (i != specific)
			{
				replace(i, specific);
			}
		}
		
		// generate pool indexes
		for (Instruction i : new ArrayList<>(instructions))
			i.prime();
		
		// rebuild pc
		int pc = 0;
		for (Instruction i : instructions)
		{
			i.setPc(pc);
			pc += i.getLength();
		}
		
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		DataOutputStream o = new DataOutputStream(b);
		for (Instruction i : instructions)
		{
			assert o.size() == i.getPc();
			i.write(o);
			assert o.size() == i.getPc() + i.getLength();
		}
		byte[] ba = b.toByteArray();
		out.writeInt(ba.length);
		out.write(ba);
	}
	
	public void clearJumpGraph()
	{
		for (Instruction i : instructions)
		{
			i.jump.clear();
			i.from.clear();
		}
	}

	public void buildJumpGraph()
	{
		clearJumpGraph();
		
		for (Instruction i : instructions)
			if (i instanceof JumpingInstruction)
				((JumpingInstruction) i).buildJumpGraph();
	}
	
	public Code getCode()
	{
		return code;
	}
	
	public Instruction findInstruction(int pc)
	{
		for (Instruction i : instructions)
			if (i.getPc() == pc)
				return i;
		return null;
	}
	
	public void lookup()
	{
		for (Instruction i : instructions)
			i.lookup();
	}
	
	public void regeneratePool()
	{
		for (Instruction i : instructions)
			i.regeneratePool();
	}

	public void replace(Instruction oldi, Instruction newi)
	{
		assert oldi != newi;
		
		assert oldi.getInstructions() == this;
		assert newi.getInstructions() == this;
		
		assert instructions.contains(oldi);
		assert !instructions.contains(newi);
		
		int i = instructions.indexOf(oldi);
		instructions.remove(oldi);
		oldi.setInstructions(null);
		instructions.add(i, newi);
		
		for (Instruction ins : oldi.from)
		{
			assert ins.getInstructions() == this;
			assert this.getInstructions().contains(ins);
			assert ins.jump.contains(oldi);
			
			ins.jump.remove(oldi);
			
			ins.jump.add(newi);
			newi.from.add(ins);
			
			ins.replace(oldi, newi);
		}
		oldi.from.clear();
		
		for (net.runelite.deob.attributes.code.Exception e : code.getExceptions().getExceptions())
			e.replace(oldi, newi);
	}
}
