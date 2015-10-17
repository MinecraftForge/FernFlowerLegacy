package pkg;

public class TestPPMM {
   // Bytecode wise ipp and ppi are identical when not using the intermediate value. 
   // We keep these seperate tests just to see the bytecode.
   public void ipp() {
      int a = 0;
      a++;
      a++;
      a++;
      a++;
   }
   public void ppi() {
      int a = 0;
      ++a;
      ++a;
      ++a;
      ++a;
   }
   public void imm() {
      int a = 0;
      a--;
      a--;
      a--;
      a--;
   }
   public void mmi() {
      int a = 0;
      --a;
      --a;
      --a;
      --a;
   }
   
   // These versions actually use the intermediate value
   public void ippf() {
      int a = 0;
      t(a++);
      t(a++);
      t(a++);
      t(a++);
   }
   public void ppif() {
      int a = 0;
      t(++a);
      t(++a);
      t(++a);
      t(++a);
   }
   public void immf() {
      int a = 0;
      t(a--);
      t(a--);
      t(a--);
      t(a--);
   }
   public void mmif() {
      int a = 0;
      t(--a);
      t(--a);
      t(--a);
      t(--a);
   }
   private static void t(int x){
   }
}