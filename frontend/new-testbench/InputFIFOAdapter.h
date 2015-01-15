#ifndef INPUTFIFOADAPTER_H
#define INPUTFIFOADAPTER_H

#include <systemc.h>

// a wrapper/adapter for bridging a SystemC FIFO to a signal-level
// read/valid enqueue interface

template <class T>
class InputFIFOAdapter : public sc_module
{
    SC_HAS_PROCESS(InputFIFOAdapter);

public:
    sc_in_clk clk;
    sc_fifo_in<T> fifoInput;

    InputFIFOAdapter(sc_module_name nm) : sc_module(nm)
    {
        m_transferCount = 0;

        SC_THREAD(transferMonitor);
        sensitive << clk.pos();

        SC_THREAD(fifoInputAdapt);
        sensitive << clk.pos();
    }

    void bindSignalInterface(sc_in<bool> & valid, sc_out<bool> & ready, sc_in<T> & data)
    {
        valid.bind(m_valid);
        ready.bind(m_ready);
        data.bind(m_data);
    }

    unsigned long int getTransferCount()
    {
        return m_transferCount;
    }

    void transferMonitor()
    {
        while(1)
        {
            if( m_valid && m_ready)
                m_transferCount++;
            wait(1);
        }
    }

    void fifoInputAdapt()
    {
        m_valid = false;
        m_data = 0xdeadbeef;

        while(1)
        {
            m_valid = false;

            T data;
            if(fifoInput.nb_read(data))
            {
                m_valid = true;
                m_data = data;

                do {
                    wait(1);
                } while(m_ready != true);

            } else
                wait(1);
        }
    }

protected:
    sc_signal<bool> m_valid;
    sc_signal<bool> m_ready;
    sc_signal<T> m_data;

    unsigned long int m_transferCount;

};

#endif // INPUTFIFOADAPTER_H
