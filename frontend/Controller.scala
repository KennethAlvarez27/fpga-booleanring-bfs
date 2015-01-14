package BFSFrontEnd

import Chisel._
import Literal._
import Node._

class FrontendController() extends Module {
  // TODO get these from global config
  val memDepth = 1024
  val addrBits = log2Up(memDepth)
  
  val io = new Bundle {
  
    // control interface
    val start = Bool(INPUT)
    val colCount = UInt(INPUT, 32)
    
    // status interface
    val state = UInt(OUTPUT, 32)
    val processedColCount = UInt(OUTPUT, 32)
    val processedNZCount = UInt(OUTPUT, 32)
    
    // SpMV data inputs
    val colLengths = Decoupled(UInt(width = 32)).flip
    val rowIndices = Decoupled(UInt(width = 32)).flip
    val dvValues = Decoupled(UInt(width = 1)).flip
    
    // interface towards result vector memory
    val resMemPort = new MemReadWritePort(1, addrBits).flip
  }
  
  // internal status registers
  // number of columns in SpM (during execution, num of
  // columns left to process)
  val regColCount = Reg(init = UInt(0, 32))
  // elements left in current column
  val regCurrentColLen = Reg(init = UInt(0, 32))
  // total NZs processed so far
  val regProcessedNZCount = Reg(init = UInt(0, 32))
  
  // default outputs
  // input queues
  io.colLengths.ready := Bool(false)
  io.rowIndices.ready := Bool(false)
  io.dvValues.ready := Bool(false)
  
  // result vector memory port
  io.resMemPort.addr := io.rowIndices.bits
  io.resMemPort.writeEn := Bool(false)
  io.resMemPort.dataIn := UInt(1)
  
  // status outputs
  io.state := regState
  io.processedColCount := io.colCount - regColCount
  io.processedNZCount := regProcessedNZCount
  
  
  // state machine definitions
  val sIdle :: sReadColLen :: sProcessColumn :: Nil = Enum(UInt(), 3)
  val regState = Reg(init = UInt(sIdle))
  
  // FSM for control
  switch ( regState ) {
    is ( sIdle ) {
      // save colCount from input
      regColCount := io.colCount
      // zero out other register values
      regCurrentColLen := UInt(0)
      regProcessedNZCount := UInt(0)
      
      when ( io.start ) { regState := sReadColLen }
    }
    
    is ( sReadColLen ) {
      // read in new column length from colLengths
      val endOfMatrix = (regColCount === UInt(0))
      // don't generate ready if there are no more cols left
      io.colLengths.ready := !endOfMatrix
      
      // when no more columns to process, go back to idle
      when ( endOfMatrix ) { regState := sIdle }
      // otherwise, wait for column length data from input queue
      .elsewhen ( io.colLengths.valid ) {
        // got column length, start processing
        regCurrentColLen := io.colLengths.bits
        regState := sProcessColumn
        // one less column to go
        regColCount := regColCount - UInt(1)
      }
    }
    
    is ( sProcessColumn ) {
      // read in new column indices + process
      val endOfColumn = ( regCurrentColLen === UInt(0) )
      // don't generate ready if there are no elements left
      io.rowIndices.ready := !endOfColumn
      
      when ( endOfColumn )
      {
        // end of column also corresponds to new x
        io.dvValues.ready := Bool(true)
        // read in a new column length
        regState := sReadColLen
      }
      .elsewhen ( io.rowIndices.valid && io.dvValues.valid ) {
        // this is the heart of the SpMV operation for BFS:
        // y[i] = x[j] | y[i]
        // - i is provided by the rowIndices FIFO
        // - j is generated by regXIndex
        // instead of computing a new result every time,
        // we can use x[j] as write enable for writing a constant 1
        io.resMemPort.writeEn := io.dvValues.bits
        
        // decrement elements left in current col
        regCurrentColLen := regCurrentColLen - UInt(1)
        
        // increment NZ counter
        regProcessedNZCount := regProcessedNZCount + UInt(1)
      }
    }
  }
}
