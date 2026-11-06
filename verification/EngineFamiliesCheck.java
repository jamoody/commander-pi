import forge.ai.*;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.neo.*;
import forge.neo.platform.*;

public final class EngineFamiliesCheck {
    static int checks;
    static void check(boolean b,String label) { if(!b)throw new AssertionError(label);checks++;System.out.println("PASS "+label); }
    static int score(StrategicThreatCheck.Fixture f,Card c) { return StrategicThreatCheck.score(f,c); }
    static Card board(StrategicThreatCheck.Fixture f,String n) { return StrategicThreatCheck.board(f,n); }
    public static void main(String[] args) throws Exception {
        NeoPortable.apply();GuiBase.setInterface(new NeoGuiBase(new FxUiDispatcher()));NeoBoot.loadEngine(null);
        try { run();System.out.println("ALL "+checks+" FAMILY CHECKS PASSED");System.exit(0); }
        catch(Throwable t){t.printStackTrace();System.exit(1);}
    }
    static void run() {
        var f=StrategicThreatCheck.fixture("Plains");
        Card k=board(f,"Kinnan, Bonder Prodigy");
        check(score(f,k)==0,"mana amplifier needs an eligible producer");
        board(f,"Forest");check(score(f,k)==0,"nonland filter excludes lands");
        board(f,"Llanowar Elves");int one=score(f,k);
        check(one==22,"one public nonland producer yields one extra mana");
        board(f,"Sol Ring");check(score(f,k)==2*one,"Kinnan adds one per producer, not one per mana produced");
        var empty=StrategicThreatCheck.fixture("Plains");
        Card copy=board(empty,"Kiki-Jiki, Mirror Breaker");
        check(score(empty,copy)==0,"copy engine without legal targets has no production");
        board(empty,"Kinnan, Bonder Prodigy");check(score(empty,copy)==0,"legendary targets excluded");
        board(empty,"Grizzly Bears");int small=score(empty,copy);check(small>0,"ordinary legal copy target recognized");
        board(empty,"Colossal Dreadmaw");int large=score(empty,copy);check(large>small,"copy target quality matters");
        board(empty,"Mulldrifter");check(score(empty,copy)>large,"copied creature ETB payoff recognized");
        var temp=AbilityFactory.getAbility("AB$ CopyPermanent | Cost$ T | ValidTgts$ Creature.nonLegendary+YouCtrl | AddKeywords$ Haste | AtEOT$ Sacrifice",copy);
        var permanent=AbilityFactory.getAbility("AB$ CopyPermanent | Cost$ T | ValidTgts$ Creature.nonLegendary+YouCtrl | AddKeywords$ Haste",copy);
        try {
            var m=StrategicThreatEvaluator.class.getDeclaredMethod("effectValue",Card.class,forge.game.spellability.SpellAbility.class,java.util.Set.class,int.class);m.setAccessible(true);
            double a=(double)m.invoke(null,copy,temp,new java.util.HashSet<>(),0),b=(double)m.invoke(null,copy,permanent,new java.util.HashSet<>(),0);
            check(b>a,"temporary copies discounted relative to permanent copies");
        }catch(Exception e){throw new RuntimeException(e);}
        var n=StrategicThreatCheck.fixture("Plains");
        Card norn=board(n,"Elesh Norn, Mother of Machines");
        check(score(n,norn)==0,"trigger modifier has no invented production on empty resource board");
        board(n,"Mulldrifter");check(score(n,norn)==0,"already-spent self ETB is not credited again");
        StrategicThreatCheck.add(n,"Plains",ZoneType.Hand);int prior=score(n,norn);
        check(prior>0,"future ETB prior depends on public hand count");
        board(n,"Soul Warden"); // currently unsupported broad ETB filter; use supported land engine too
        board(n,"Tatyova, Benthic Druid");
        check(score(n,norn)>prior,"visible recurring entry payoff increases multiplier value");
        var enemy=new StrategicThreatCheck.Fixture(n.game(),n.opponent(),n.ai());
        int beforeDenial=score(n,norn);
        StrategicThreatCheck.add(enemy,"Plains",ZoneType.Hand);int denied=score(n,norn);
        check(denied>beforeDenial,"opposing future entry effects contribute to denial value");
        var hidden=StrategicThreatCheck.fixture("Island");
        Card norn2=board(hidden,"Elesh Norn, Mother of Machines");
        StrategicThreatCheck.add(hidden,"Colossal Dreadmaw",ZoneType.Hand);
        check(score(hidden,norn2)==prior,"hidden hand and library identities do not change entry prior");
        System.setProperty("forge.ai.disableStrategicThreat","true");
        check(score(f,k)==0 && score(empty,copy)==0 && score(n,norn)==0,"baseline switch disables all new families");
        System.clearProperty("forge.ai.disableStrategicThreat");
    }
}
