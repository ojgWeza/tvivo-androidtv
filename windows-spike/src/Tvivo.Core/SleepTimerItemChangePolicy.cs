namespace Tvivo.Core;

public enum SleepTimerItemChangeSetting { Clear, Keep }

public static class SleepTimerItemChangePolicy
{
    public static bool ShouldClear(SleepTimerMode? mode, SleepTimerItemChangeSetting setting) =>
        mode == SleepTimerMode.EndOfCurrentItem || setting == SleepTimerItemChangeSetting.Clear;
}
